@file:UseSerializers(InstantAsStringSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import no.nav.sokos.oppgjorsrapporter.rapport.Api.RapportDTO
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.Varsel
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer

object FrontendApi {
    @Serializable
    data class VarselOppgittDTO(val varselOpprettet: Instant, val varslingOppgitt: Instant, val rapport: RapportDTO) {
        constructor(pair: Pair<Varsel, Rapport>) : this(pair.first.opprettet, pair.first.oppgitt!!, RapportDTO(pair.second))
    }

    fun Route.rapportApi() {
        val varselService: VarselService by application.dependencies

        get("/api/rapport/frontend/oppgitt-varsling") { call.respond(varselService.finnOppgitte().map(::VarselOppgittDTO)) }
    }
}
