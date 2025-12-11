package no.nav.sokos.oppgjorsrapporter.innhold.generator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics

class RefusjonArbeidsgiverInnholdGenerator(
    private val baseUrl: URI,
    private val eregService: EregService,
    private val client: HttpClient,
    private val metrics: Metrics,
) {
    private val logger = KotlinLogging.logger {}

    fun genererCsvInnhold(refusjonsRapportBestilling: RefusjonsRapportBestilling): ByteString {
        return refusjonsRapportBestilling.tilCSV().encodeToByteString()
    }

    suspend fun genererPdfInnhold(refusjonsRapportBestilling: RefusjonsRapportBestilling): ByteString {
        return run {
            val arbeidsgiverNavnOgAdresse = eregService.hentOrganisasjonsNavnOgAdresse(refusjonsRapportBestilling.header.orgnr)
            val payload = refusjonsRapportBestilling.tilPdfPayload(arbeidsgiverNavnOgAdresse)
            val pdfGenUrl = baseUrl.resolve("/api/v1/genpdf/oppgjorsrapporter/refusjonarbeidsgiver").toURL()
            val response =
                client.post(pdfGenUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            metrics.tellEksternEndepunktRequest(response, pdfGenUrl.path)

            when {
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
    }
}
