package no.nav.sokos.oppgjorsrapporter.tokenexchange

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.net.URI
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.UnexpectedResponseException
import no.nav.sokos.oppgjorsrapporter.tokenexchange.kontrakter.TokenErrorResponse
import no.nav.sokos.oppgjorsrapporter.tokenexchange.kontrakter.TokenResponse

interface TokenExchangeService {
    suspend fun getOboToken(token: String): String
}

class TokenExchangeServiceImpl(
    private val tokenExchangeEndpoint: String,
    private val targetScope: String,
    private val client: HttpClient,
    private val metrics: Metrics,
) : TokenExchangeService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getOboToken(token: String): String {
        val response =
            client.submitForm(
                tokenExchangeEndpoint,
                parameters {
                    set("target", targetScope)
                    set("user_token", token)
                    set("identity_provider", "azuread")
                },
            )

        metrics.tellEksternEndepunktRequest(response, URI(tokenExchangeEndpoint).path)

        if (response.status.isSuccess()) {
            logger.info { "Vellykket henting av obo token" }
            return response.body<TokenResponse.Success>().accessToken
        } else {
            val errorBody = TokenResponse.Error(response.body<TokenErrorResponse>(), response.status)
            logger.warn(TEAM_LOGS_MARKER) { "Feil ved henting av obo token. Status: ${response.status} body: $errorBody" }
            throw UnexpectedResponseException("Unventet svar ved henting av obo token")
        }
    }
}

object LocalTokenExchangeService : TokenExchangeService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getOboToken(token: String): String {
        logger.info(TEAM_LOGS_MARKER) { "LocalTokenExchangeService - svarer med fake obotoken" }
        return "fake obotoken"
    }
}

object TokenExchangeHttpClientSetup : HttpClientSetup {
    override val jsonConfig: Json = commonJsonConfig
}
