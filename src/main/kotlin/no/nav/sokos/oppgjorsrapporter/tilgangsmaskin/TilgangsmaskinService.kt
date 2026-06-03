package no.nav.sokos.oppgjorsrapporter.tilgangsmaskin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URI
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.kontrakter.ProblemDetailApiResponse
import no.nav.sokos.utils.Fnr

class TilgangsmaskinService(private val baseUrl: URI, private val client: HttpClient, private val metrics: Metrics) {
    private val logger = KotlinLogging.logger {}

    suspend fun sjekkTilgang(oboToken: String, fnr: Fnr): ProblemDetailApiResponse? {
        metrics.tellTilgangsmaskinKall()

        return metrics.coRecord({ result -> metrics.tilgangsmaskinKallTimer.withTags("feilet", result.isFailure.toString()) }) {
            val response =
                client.post("${baseUrl}/api/v1/kjerne") {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    bearerAuth(oboToken)
                    setBody(fnr)
                }
            when (response.status) {
                HttpStatusCode.NoContent -> null
                HttpStatusCode.Forbidden -> {
                    response.body<ProblemDetailApiResponse>().also {
                        logger.info("Tilgangsmaskinen avviser tilgang")
                        logger.info(TEAM_LOGS_MARKER) { "Tilgangsmaskinen avviser tilgang: $it" }
                    }
                }
                else -> {
                    val body = response.bodyAsText()
                    logger.warn(TEAM_LOGS_MARKER) { "Feil fra tilgangsmaskinen. Status: ${response.status} body: <$body>" }
                    throw UnexpectedResponseException(message = "Uventet svar fra tilgangsmaskinen")
                }
            }
        }
    }
}

object TilgangsmaskinHttpClientSetup : HttpClientSetup {
    override val jsonConfig: Json = commonJsonConfig
}

class UnexpectedResponseException(override val message: String) : Exception(message)
