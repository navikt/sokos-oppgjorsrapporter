package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.RapportBestillingMsg
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.TrekkHendBestilling
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.tilCSV
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredPdfMapper.mapTilTrekkKredRapportPdfPayload

private const val REFUSJON_ARBG_PDFGEN_PATH = "/api/v1/genpdf/oppgjorsrapporter/ref-arbg"
private const val TREKK_KRED_PDFGEN_PATH = "/api/v1/genpdf/oppgjorsrapporter/trekk-kred"
private const val TREKK_HEND_PDFGEN_PATH = "/api/v1/genpdf/oppgjorsrapporter/trekk-hend"

class RapportGenerator(
    private val pdfgenBaseUrl: URI,
    private val client: HttpClient,
    private val metrics: Metrics,
    private val clock: Clock,
) {

    private val logger = KotlinLogging.logger {}

    fun genererCsvInnhold(bestilling: RapportBestillingMsg): ByteString {
        return when (bestilling) {
            is RefusjonsRapportBestilling -> {
                bestilling.tilCSV()
            }
            is TrekkKredRapportBestilling -> {
                bestilling.toCsv()
            }
            is TrekkHendBestilling -> {
                bestilling.toCsv()
            }
        }.encodeToByteString(Charsets.ISO_8859_1)
    }

    suspend fun genererPdfInnhold(bestilling: RapportBestillingMsg, arbeidsgiverNavnOgAdresse: OrganisasjonsNavnOgAdresse): ByteString {
        val pdfgenPayload =
            when (bestilling) {
                is RefusjonsRapportBestilling ->
                    RefusjonsRapportPdfPayload(
                            bestilling = bestilling,
                            organisasjonsNavnOgAdresse = arbeidsgiverNavnOgAdresse,
                            rapportSendt = LocalDate.now(clock),
                        )
                        .let { payload ->
                            val json = PdfgenHttpClientSetup.jsonConfig.encodeToString(payload)
                            logger.debug(TEAM_LOGS_MARKER) { "Sender ${payload.javaClass.simpleName} json til pdfgen: $json" }
                            json
                        }

                is TrekkKredRapportBestilling ->
                    bestilling
                        .mapTilTrekkKredRapportPdfPayload(
                            organisasjonsNavnOgAdresse = arbeidsgiverNavnOgAdresse,
                            rapportSendt = LocalDate.now(clock),
                        )
                        .let { payload ->
                            val json = PdfgenHttpClientSetup.jsonConfig.encodeToString(payload)
                            logger.debug(TEAM_LOGS_MARKER) { "Sender ${payload.javaClass.simpleName} json til pdfgen: $json" }
                            json
                        }

                is TrekkHendBestilling ->
                    bestilling.mapTilTrekkHendPdfPayload().let { payload ->
                        val json = PdfgenHttpClientSetup.jsonConfig.encodeToString(payload)
                        logger.debug(TEAM_LOGS_MARKER) { "Sender ${payload.javaClass.simpleName} json til pdfgen: $json" }
                        json
                    }
            }
        val pdfgenUrl = bestilling.resolvePdfgenUrl()
        val response =
            try {
                client.post(pdfgenUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(pdfgenPayload)
                }
            } catch (e: Exception) {
                logger.error(TEAM_LOGS_MARKER, e) {
                    "PDF-generering for ${bestilling.javaClass.simpleName} feilet; payload-lengde=${pdfgenPayload.length}, json='$pdfgenPayload'"
                }
                throw e
            }
        metrics.tellEksternEndepunktRequest(response, pdfgenUrl.path)

        return when {
            response.status.isSuccess() -> {
                val pdfInnhold = response.body<ByteArray>()
                ByteString(pdfInnhold)
            }

            else -> {
                val apiError = ApiError(response, "Noe gikk galt ved kall mot PDF-generator-tjenesten")

                logger.error { "Feil ved kall mot PDF-generator-tjenesten $apiError" }
                logger.error(TEAM_LOGS_MARKER) {
                    "Feil ved kall mot PDF-generator-tjenesten; response=$response, bestillingstype=${bestilling.javaClass.simpleName}, payload-lengde=${pdfgenPayload.length}, json='$pdfgenPayload'"
                }
                throw RuntimeException("Feil ved kall mot PDF-generator-tjenesten: $apiError")
            }
        }
    }

    private fun RapportBestillingMsg.resolvePdfgenUrl() =
        when (this) {
            is RefusjonsRapportBestilling -> pdfgenBaseUrl.resolve(REFUSJON_ARBG_PDFGEN_PATH).toURL()
            is TrekkKredRapportBestilling -> pdfgenBaseUrl.resolve(TREKK_KRED_PDFGEN_PATH).toURL()
            is TrekkHendBestilling -> pdfgenBaseUrl.resolve(TREKK_HEND_PDFGEN_PATH).toURL()
        }
}

object PdfgenHttpClientSetup : HttpClientSetup {
    override val jsonConfig: Json = commonJsonConfig
}
