package no.nav.sokos.oppgjorsrapporter.fager

import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.AuthClientIdentityProvider
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType


interface AltinnTilgangerService {
	suspend fun hentAltinnTilganger(token: String): AltinnTilganger?
}

class AltinnTilgangerServiceImpl(securityProperties: PropertiesConfig.SecurityProperties, val authClient: AuthClient, private val client: HttpClient) : AltinnTilgangerService {
	private val logger = KotlinLogging.logger {}
    private val altinnTilgangerProxyUrl = securityProperties.altinnTilganger.altinnTilgangerProxyUrl
    private val altinnTilgangerAudience = securityProperties.altinnTilganger.altinnTilgangerAudience

    override suspend fun hentAltinnTilganger(token: String): AltinnTilganger? {
        try {
            logger.debug("henter Altinn tilganger på URL $altinnTilgangerProxyUrl")
	        val exchangedToken = authClient.exchange(provider = AuthClientIdentityProvider.TOKEN_X, target = altinnTilgangerAudience, userToken = token).accessToken

	        val body = AltinnTilgangerRequest(filter = AltinnTilgangerFilter(altinn3Tilganger = RapportType.entries.map { it.altinnRessurs }))

            val response: HttpResponse = client.post {
                url(altinnTilgangerProxyUrl.toURL())
				bearerAuth(exchangedToken)
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body)
            }
            val decoder = Json { ignoreUnknownKeys = true }
            return  decoder.decodeFromString<AltinnTilganger>(response.body())
        } catch (e: Exception) {
            logger.error(TEAM_LOGS_MARKER, e) { "Feil ved kall til Altinn tilganger $e" }
            logger.error("Feil ved kall til Altinn tilganger. Sjekk sensitiv logg for mer info")
            return null
        }
    }
}

object LocalhostAltinnTilgangerService : AltinnTilgangerService {
	private val logger = KotlinLogging.logger {}

	override suspend fun hentAltinnTilganger(token: String): AltinnTilganger {
		logger.info(TEAM_LOGS_MARKER) { "Lokal mocket altinntilganger" }
		return AltinnTilganger(
			hierarki = listOf(
				AltinnTilgang(
					orgnr = "987654321",
					altinn3Tilganger = setOf(RapportType.`ref-arbg`.altinnRessurs),
					altinn2Tilganger = setOf(),
					underenheter = listOf(
						AltinnTilgang(
							orgnr = "123456789",
							altinn3Tilganger = setOf(RapportType.`ref-arbg`.altinnRessurs),
							altinn2Tilganger = setOf(),
							underenheter = listOf(),
							navn = "Bedrift",
							organisasjonsform = "BEDR"
						)
					),
					navn = "Organisasjon",
					organisasjonsform = "ORGL"
				)
			),
			orgNrTilTilganger = mapOf(),
			tilgangTilOrgNr = mapOf(),
			isError = false
		)
	}
}
