package no.nav.sokos.oppgjorsrapporter.auth

import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import java.net.URI
import kotlinx.coroutines.runBlocking
import no.nav.helsearbeidsgiver.utils.log.sikkerLogger

interface AuthClient {
    fun tokenGetter(identityProvider: AuthClientIdentityProvider, target: String): () -> String

    suspend fun altinnExchange(maskinportenTokenGetter: String): String
}

class NoOpAuthClient : AuthClient {
    override fun tokenGetter(identityProvider: AuthClientIdentityProvider, target: String): () -> String = { "dummy-token" }

    override suspend fun altinnExchange(maskinportenTokenGetter: String): String = "dummy-token"
}

class DefaultAuthClient(
    private val tokenEndpoint: String,
    private val tokenExchangeEndpoint: String,
    private val tokenIntrospectionEndpoint: String,
    private val altinn3BaseUrl: URI,
) : AuthClient {
    private val sikkerLogger = sikkerLogger()
    private val httpClient = createHttpClient()

    override fun tokenGetter(identityProvider: AuthClientIdentityProvider, target: String): () -> String = {
        runBlocking { token(identityProvider, target).accessToken }
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

    internal suspend fun exchange(provider: AuthClientIdentityProvider, target: String, userToken: String): TokenResponse =
        try {
            httpClient
                .submitForm(
                    url = tokenExchangeEndpoint,
                    formParameters =
                        parameters {
                            identityProvider(provider)
                            target(target)
                            userToken(userToken)
                        },
                )
                .body()
        } catch (e: ResponseException) {
            e.logAndRethrow()
        }

    internal suspend fun introspect(provider: AuthClientIdentityProvider, accessToken: String): TokenIntrospectionResponse =
        httpClient
            .submitForm(
                url = tokenIntrospectionEndpoint,
                formParameters =
                    parameters {
                        identityProvider(provider)
                        token(accessToken)
                    },
            )
            .body()

    override suspend fun altinnExchange(token: String): String {
        val tokenAltinn3ExchangeEndpoint: URI = altinn3BaseUrl.resolve("/authentication/api/v1/exchange/maskinporten")

        return httpClient.get(tokenAltinn3ExchangeEndpoint.toURL()) { bearerAuth(token) }.bodyAsText().replace("\"", "")
    }

    private suspend fun ResponseException.logAndRethrow(): Nothing {
        val error = response.body<ErrorResponse>()
        val msg = "Klarte ikke hente token. Feilet med status '${response.status}' og feilmelding '${error.errorDescription}'."

        sikkerLogger.error(msg)
        throw this
    }
}
