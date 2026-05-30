package no.nav.sokos.oppgjorsrapporter.tilgangsmaskin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URI
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.kontrakter.PersonDetailResponseDTO
import no.nav.sokos.oppgjorsrapporter.tokenexchange.TokenExchangeService
import no.nav.sokos.utils.Fnr

class TilgangsmaskinService(
    private val baseUrl: URI,
    private val client: HttpClient,
    val tokenExchangeService: TokenExchangeService,
    private val metrics: Metrics,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun sjekkTilgang(token: String, fnr: Fnr): PersonDetailResponseDTO? {
        val onBehalfOfToken = tokenExchangeService.getOboToken(token)
        val response =
            client.post("${baseUrl}/api/v1/kjerne") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $onBehalfOfToken}") // TODO bør kanskje kalle onBehalfOfToken tjenesten her
                setBody(fnr)
            }

        return when (response.status) {
            HttpStatusCode.NoContent -> null
            HttpStatusCode.Forbidden -> response.body()
            else ->
                throw UnexpectedResponseException( // TODO why not just log instead of throw?
                    message = "Uventet svar fra tilgangsmaskinen",
                    statusCode = response.status,
                    response = response.body(),
                )
        }
    }
}

object TilgangsmaskinHttpClientSetup : HttpClientSetup {
    override val jsonConfig: Json = commonJsonConfig
}

// TODO sjekk hvordan vi kaster exception
class UnexpectedResponseException(message: String, val statusCode: HttpStatusCode, val response: String?) : Exception(message)
