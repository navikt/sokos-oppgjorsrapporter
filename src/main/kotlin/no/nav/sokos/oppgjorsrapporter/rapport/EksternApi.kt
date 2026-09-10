package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.util.getValue
import io.micrometer.core.instrument.Tag
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.TokenX
import no.nav.sokos.oppgjorsrapporter.auth.autentisertBruker
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.tilgang.TilgangService
import no.nav.sokos.utils.OrgNr

private val logger = KotlinLogging.logger {}

object EksternApi {
    @Serializable data class EksternRapportListeFilterRequest(val orgnr: OrgNr, val rapportType: RapportType)
}

fun Route.eksternApi() {
    val rapportService: RapportService by application.dependencies
    val tilgangService: TilgangService by application.dependencies
    val metrics: Metrics by application.dependencies

    route("/api/ekstern/v1") {
        get("/{id}") {
            val id: Long by call.request.pathVariables
            val rapportId = Rapport.Id(id)

            when (val bruker = autentisertBruker()) {
                is TokenX -> {
                    val rapporterMedNedlastingsinfo = rapportService.listRapporterMedEksternNedlastingsinfo(rapportId)
                    if (rapporterMedNedlastingsinfo.isEmpty()) {
                        return@get call.respond(HttpStatusCode.NotFound)
                    }

                    val (orgnr, type) =
                        runCatching { rapporterMedNedlastingsinfo.map { it.rapportInfo.orgnr to it.rapportInfo.type }.distinct().single() }
                            .getOrElse {
                                val feil =
                                    "Oppslag etter tilgrensende rapporter for $rapportId returnerte rapporter for andre orgnr eller rapport-typer"
                                logger.error(feil)
                                logger.error(TEAM_LOGS_MARKER) { "$feil: $rapporterMedNedlastingsinfo" }
                                return@get call.respond(HttpStatusCode.InternalServerError)
                            }

                    if (!tilgangService.harTilgangTilRessurs(bruker, type, orgnr)) {
                        return@get call.respond(HttpStatusCode.NotFound)
                    }

                    metrics.rapportUtvidetReturnertAntall
                        .withTags(listOf(Tag.of("auth_type", bruker.authType), Tag.of("rapporttype", type.name)))
                        .record(rapporterMedNedlastingsinfo.size.toDouble())

                    call.respond(Api.TilgrensendeRapporterDTO(rapportId, rapporterMedNedlastingsinfo))
                }
                else -> return@get call.respond(HttpStatusCode.Forbidden)
            }
        }

        post {
            val body = call.receive<EksternApi.EksternRapportListeFilterRequest>()
            when (val bruker = autentisertBruker()) {
                is TokenX -> {
                    if (!tilgangService.harTilgangTilRessurs(bruker, body.rapportType, body.orgnr)) {
                        return@post call.respond(HttpStatusCode.NotFound)
                    }

                    val rapporter = rapportService.listRapporterMedEksternNedlastingsinfo(body.orgnr, body.rapportType)
                    if (rapporter.isEmpty()) {
                        return@post call.respond(HttpStatusCode.NotFound)
                    }

                    metrics.rapportPrOrgReturnertAntall
                        .withTags(listOf(Tag.of("auth_type", bruker.authType), Tag.of("rapporttype", body.rapportType.name)))
                        .record(rapporter.size.toDouble())

                    call.respond(Api.TilgrensendeRapporterDTO(rapportId = Rapport.Id(0), rapporterMedNedlastingsinfo = rapporter))
                }
                else -> {
                    logger.debug { "En ikke-TokenX bruker forsøkte å nå orgnr-API" }
                    return@post call.respond(HttpStatusCode.Forbidden)
                }
            }
        }
    }
}
