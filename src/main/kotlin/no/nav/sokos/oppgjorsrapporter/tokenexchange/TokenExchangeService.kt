package no.nav.sokos.oppgjorsrapporter.tokenexchange

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters
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

        if (response.status.isSuccess()) {
            val respBody = response.body<TokenResponse.Success>()
            return respBody.accessToken
        } else {
            val resp = TokenResponse.Error(response.body<TokenErrorResponse>(), response.status)
            throw UnexpectedResponseException(
                "Kunne ikke autentisere mot $targetScope feilmelding: ${resp.error.errorDescription}",
                resp.status,
                resp.error.error,
            )
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
