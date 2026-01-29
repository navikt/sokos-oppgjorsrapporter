package no.nav.sokos.oppgjorsrapporter.auth

import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import java.net.URI
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER

interface AuthClient {
    fun tokenGetter(identityProvider: AuthClientIdentityProvider, target: String): suspend () -> String

    suspend fun altinnExchange(maskinportenToken: String): String
}

class NoOpAuthClient : AuthClient {
    override fun tokenGetter(identityProvider: AuthClientIdentityProvider, target: String): suspend () -> String = { "dummy-token" }

    override suspend fun altinnExchange(maskinportenToken: String): String = "dummy-token"
}

class DefaultAuthClient(private val tokenEndpoint: String, private val altinn3BaseUrl: URI) : AuthClient {
    private val logger = KotlinLogging.logger {}
    private val httpClient = createHttpClient()

    override fun tokenGetter(identityProvider: AuthClientIdentityProvider, target: String): suspend () -> String = {
        token(identityProvider, target).accessToken
    }

    internal suspend fun token(provider: AuthClientIdentityProvider, target: String): TokenResponse =
        try {
            httpClient
                .submitForm(
                    url = tokenEndpoint,
                    formParameters =
                        parameters {
                            identityProvider(provider)
                            target(target)
                        },
                )
                .body()
        } catch (e: ResponseException) {
            e.logAndRethrow()
        }

    override suspend fun altinnExchange(maskinportenToken: String): String {
        val tokenAltinn3ExchangeEndpoint: URI = altinn3BaseUrl.resolve("/authentication/api/v1/exchange/maskinporten")

        return httpClient.get(tokenAltinn3ExchangeEndpoint.toURL()) { bearerAuth(maskinportenToken) }.bodyAsText().replace("\"", "")
    }

    private suspend fun ResponseException.logAndRethrow(): Nothing {
        val error = response.body<ErrorResponse>()
        logger.error(TEAM_LOGS_MARKER) {
            "Klarte ikke hente token. Feilet med status '${response.status}' og feilmelding '${error.errorDescription}'."
        }
        throw this
    }
}
