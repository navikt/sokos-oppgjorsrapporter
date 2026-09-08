package no.nav.sokos.oppgjorsrapporter.fager

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import no.nav.sokos.oppgjorsrapporter.auth.TokenX
import no.nav.sokos.oppgjorsrapporter.auth.autentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.hentJwtToken
import no.nav.sokos.oppgjorsrapporter.config.AuthenticationType

object Api {
    @Serializable data class TilgangTilVirksomheterDto(val tilgang: String, val virksomheter: List<VirksomhetDto>)

    @Serializable data class VirksomhetDto(val orgnr: String, val navn: String, val underenheter: List<VirksomhetDto>)
}

fun Route.altinnTilgangerApi() {
    val altinnTilgangerService: AltinnTilgangerService by application.dependencies

    get("/api/organisasjoner") {
        autentisertBruker().let { bruker ->
            when (bruker) {
                is TokenX -> {
                    val token = hentJwtToken(AuthenticationType.EKSTERNE_BRUKERE_TOKENX)
                    val altinnTilganger = altinnTilgangerService.hentAltinnTilganger(token.encodedToken)
                    val tilgangTilVirksomheter = altinnTilganger?.tilgangTilVirksomheterDto() ?: listOf()
                    call.respond(tilgangTilVirksomheter)
                }
                else -> {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }
    }
}

fun AltinnTilganger.tilgangTilVirksomheterDto(): List<Api.TilgangTilVirksomheterDto> {
    // 1. Rekursiv hjelpefunksjon for å mappe hierarkiet til DTO-formatet
    fun mapVirksomhet(tilgang: AltinnTilgang): Api.VirksomhetDto {
        val underenheterMapped = tilgang.underenheter.map { mapVirksomhet(it) }

        return Api.VirksomhetDto(orgnr = tilgang.orgnr, navn = tilgang.navn, underenheter = underenheterMapped)
    }

    // 2. Flat ut par av (tilgang, virksomhet) fra hele toppnivå-hierarkiet
    val flattedePar =
        hierarki.flatMap { toppNivaa ->
            val virksomhetDto = mapVirksomhet(toppNivaa)
            toppNivaa.altinn3Tilganger.map { tilgang -> tilgang to virksomhetDto }
        }

    // 3. Grupper på tilgang-strengen og transformer til den endelige DTO-listen
    return flattedePar
        .groupBy({ it.first }, { it.second }) // Grupperer List<Pair<String, VirksomhetDto>> til Map<String, List<VirksomhetDto>>
        .map { (tilgang, virksomheter) -> Api.TilgangTilVirksomheterDto(tilgang = tilgang, virksomheter = virksomheter) }
}
