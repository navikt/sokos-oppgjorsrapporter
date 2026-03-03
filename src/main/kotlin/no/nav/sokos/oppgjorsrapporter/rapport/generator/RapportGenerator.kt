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
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.RapportBestillingMsg
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.tilCSV
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredPdfMapper.mapTilTrekkKredRapportPdfPayload

private const val REFUSJON_ARBG_PDFGEN_PATH = "/api/v1/genpdf/oppgjorsrapporter/refusjon-arbg-sortert-etter-ytelse"
private const val TREKK_KRED_PDFGEN_PATH = "/api/v1/genpdf/oppgjorsrapporter/T14"

class RapportGenerator(private val baseUrl: URI, private val client: HttpClient, private val metrics: Metrics, private val clock: Clock) {

    private val logger = KotlinLogging.logger {}

    fun genererCsvInnhold(bestilling: RapportBestillingMsg): ByteString {
        return when (bestilling) {
            is RefusjonsRapportBestilling -> {
                bestilling.tilCSV()
            }
            is TrekkKredRapportBestilling -> {
                bestilling.toCsv_V1()
            }
        }.encodeToByteString(Charsets.ISO_8859_1)
    }

    suspend fun genererPdfInnhold(bestilling: RapportBestillingMsg, arbeidsgiverNavnOgAdresse: OrganisasjonsNavnOgAdresse): ByteString {
        val pdfgenUrl = bestilling.resolvePdfGenPdfgenUrl()
        val pdfgenPayload =
            when (bestilling) {
                is RefusjonsRapportBestilling -> {
                    RefusjonsRapportPdfPayload(
                        bestilling = bestilling,
                        organisasjonsNavnOgAdresse = arbeidsgiverNavnOgAdresse,
                        rapportSendt = LocalDate.now(clock),
                    )
                }
                is TrekkKredRapportBestilling -> {
                    bestilling.mapTilTrekkKredRapportPdfPayload(
                        organisasjonsNavnOgAdresse = arbeidsgiverNavnOgAdresse,
                        rapportSendt = LocalDate.now(clock),
                    )
                }
            }
        val response =
            client.post(pdfgenUrl) {
                contentType(ContentType.Application.Json)
                setBody(pdfgenPayload)
            }
        metrics.tellEksternEndepunktRequest(response, pdfgenUrl.path)

        return when {
            response.status.isSuccess() -> {
                val pdfInnhold = response.body<ByteArray>()
                ByteString(pdfInnhold)
            }

            else -> {
                val apiError = ApiError(response, "Noe gikk galt ved kall mot PDF-generator tjenesten")

                logger.error { "Feil ved kall mot PDF-generator tjenesten $apiError" }
                throw RuntimeException("Feil ved kall mot PDF-generator tjenesten: $apiError")
            }
        }
    }

    private fun RapportBestillingMsg.resolvePdfGenPdfgenUrl() =
        when (this) {
            is RefusjonsRapportBestilling -> baseUrl.resolve(REFUSJON_ARBG_PDFGEN_PATH).toURL()
            is TrekkKredRapportBestilling -> baseUrl.resolve(TREKK_KRED_PDFGEN_PATH).toURL()
        }
}

object PdfgenHttpClientSetup : HttpClientSetup {
    override val jsonConfig: Json = commonJsonConfig
}
