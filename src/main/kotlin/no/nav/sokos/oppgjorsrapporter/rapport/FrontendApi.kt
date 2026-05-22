@file:UseSerializers(InstantAsStringSerializer::class, LocalDateAsStringSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.util.getValue
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.autentisertBruker
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.entraid.InternTilgangService
import no.nav.sokos.oppgjorsrapporter.rapport.Api.RapportDTO
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.Varsel
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import org.threeten.extra.LocalDateRange

object FrontendApi {
    private val logger = KotlinLogging.logger {}

    @Serializable
    sealed interface RapportSoek {
        val fraDato: LocalDate
        val tilDato: LocalDate
        val rapportType: RapportType
        val inkluderArkiverte: Boolean

        fun datoRange(): LocalDateRange = LocalDateRange.ofClosed(fraDato, tilDato)
    }

    @Serializable
    data class RapportFnrSoek(
        val fnr: Fnr,
        override val fraDato: LocalDate,
        override val tilDato: LocalDate,
        override val rapportType: RapportType,
        override val inkluderArkiverte: Boolean = true,
    ) : RapportSoek

    data class RapportUnderenhetSoek(
        val underenhet: OrgNr,
        override val fraDato: LocalDate,
        override val tilDato: LocalDate,
        override val rapportType: RapportType,
        override val inkluderArkiverte: Boolean = true,
    ) : RapportSoek

    @Serializable
    data class VarselOppgittDTO(val varselOpprettet: Instant, val varslingOppgitt: Instant, val rapport: RapportDTO) {
        constructor(pair: Pair<Varsel, Rapport>) : this(pair.first.opprettet, pair.first.oppgitt!!, RapportDTO(pair.second))
    }

    fun Route.rapportApi() {
        val varselService: VarselService by application.dependencies
        val rapportService: RapportService by application.dependencies
        val internTilgangService: InternTilgangService by application.dependencies

        get("/api/rapport/frontend/oppgitt-varsling") { call.respond(varselService.finnOppgitte().map(::VarselOppgittDTO)) }

        get("/api/rapport/frontend/{id}/audit") {
            val id: Long by call.request.pathVariables
            val events = rapportService.hentAuditLog(RapportAuditKriterier(Rapport.Id(id), null, null))
            if (events.isNotEmpty()) {
                call.respond(events)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/api/rapport/frontend/tilgang") {
            autentisertBruker().let { bruker ->
                when (bruker) {
                    is EntraId -> call.respond(internTilgangService.rapportTyperBrukerHarTilgangTil(bruker))
                    else -> {
                        logger.warn(TEAM_LOGS_MARKER) {
                            "En IKKE EntraId bruker: $bruker har ikke tilgang til /api/rapport/frontend/tilgang"
                        }
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
            }
        }
        post("/api/rapport/frontend/nevnt-sok") {
            val reqBody = call.receive<RapportSoek>()
            autentisertBruker().let { bruker ->
                when (bruker) {
                    is EntraId -> {
                        val rapporter =
                            when (reqBody) {
                                is RapportFnrSoek -> {
                                    if (!internTilgangService.rapportTyperBrukerHarTilgangTil(bruker).contains(reqBody.rapportType)) {
                                        logger.info { "Bruker $bruker har ikke tilgang til rapporttype ${reqBody.rapportType}" }
                                        return@post call.respond(HttpStatusCode.Forbidden)
                                    }
                                    with(reqBody) { rapportService.rapportSoek(fnr, datoRange(), inkluderArkiverte, rapportType) }
                                }
                                is RapportUnderenhetSoek -> TODO()
                            }
                        call.respond(
                            rapporter.filter { internTilgangService.harTilgangTilRessurs(bruker, it.orgnr, it.type) }.map(::RapportDTO)
                        )
                    }
                    else -> {
                        logger.warn(TEAM_LOGS_MARKER) { "En IKKE EntraId bruker: $bruker har ikke tilgang til /api/rapport/frontend" }
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
            }
        }
    }
}
