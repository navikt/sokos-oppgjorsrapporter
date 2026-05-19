@file:UseSerializers(InstantAsStringSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.util.getValue
import java.time.Instant
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

object FrontendApi {
    private val logger = KotlinLogging.logger {}

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
    }
}
