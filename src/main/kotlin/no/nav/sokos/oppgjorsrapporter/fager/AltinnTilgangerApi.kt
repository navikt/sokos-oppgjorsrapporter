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
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

object Api {
    @Serializable data class VirksomhetDto(val orgnr: String, val navn: String, val underenheter: List<VirksomhetDto>)
}

fun Route.altinnTilgangerApi() {
    val altinnTilgangerService: AltinnTilgangerService by application.dependencies

    get("/api/organisasjoner/v1/{rapportType}") {
        val rapportType =
            try {
                call.pathParameters["rapportType"]?.let { RapportType.valueOf(it) } ?: return@get call.respond(HttpStatusCode.BadRequest)
            } catch (_: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest)
            }

        autentisertBruker().let { bruker ->
            when (bruker) {
                is TokenX -> {
                    val token = hentJwtToken(AuthenticationType.EKSTERNE_BRUKERE_TOKENX)
                    val altinnTilganger = altinnTilgangerService.hentAltinnTilganger(rapportType, token.encodedToken)
                    val tilgangTilVirksomheter = altinnTilganger?.tilgangTilVirksomheterDto() ?: emptyList()
                    call.respond(tilgangTilVirksomheter)
                }
                else -> {
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
        }
    }
}

fun AltinnTilganger.tilgangTilVirksomheterDto(): List<Api.VirksomhetDto> {
    // 1. Rekursiv hjelpefunksjon for å mappe hierarkiet til DTO-formatet
    fun mapVirksomhet(tilgang: AltinnTilgang): Api.VirksomhetDto {
        val underenheterMapped = tilgang.underenheter.map { mapVirksomhet(it) }

        return Api.VirksomhetDto(orgnr = tilgang.orgnr, navn = tilgang.navn, underenheter = underenheterMapped)
    }

    // 2. Flat ut par av (tilgang, virksomhet) fra hele toppnivå-hierarkiet
    return hierarki.map { toppNivaa -> mapVirksomhet(toppNivaa) }
}
