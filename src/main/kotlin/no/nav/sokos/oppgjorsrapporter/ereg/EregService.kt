package no.nav.sokos.oppgjorsrapporter.ereg

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.generator.ApiError
import no.nav.sokos.oppgjorsrapporter.rapport.generator.eregErrorMessage
import org.slf4j.MDC

class EregService(private val baseUrl: URI, private val client: HttpClient, private val metrics: Metrics) {
    private val logger = KotlinLogging.logger {}

    suspend fun hentOrganisasjonsNavnOgAdresse(orgnr: String): OrganisasjonsNavnOgAdresse {
        return run {
            logger.info { "Henter organisasjonsnavn og adresse for $orgnr fra Ereg." }
            val eregUrl = baseUrl.resolve("/v2/organisasjon/$orgnr/noekkelinfo").toURL()
            val response = client.get(eregUrl) { header("Nav-Call-Id", MDC.get("x-correlation-id")) }
            metrics.tellEksternEndepunktRequest(response, "/v2/organisasjon/{orgnr}/noekkelinfo")

            when {
                response.status.isSuccess() -> {
                    val organisasjonInfo = response.body<Organisasjon>().tilOrganisasjonsNavnOgAdresse()
                    logger.info { "Fant organisasjonsnavn fra Ereg: $organisasjonInfo" }
                    organisasjonInfo
                }

                else -> {
                    val apiError = ApiError(response, response.eregErrorMessage() ?: "Noe gikk galt ved oppslag mot Ereg-tjenesten")
                    logger.error { "Feil ved oppslag mot Ereg $apiError" }
                    throw RuntimeException("Feil ved oppslag mot Ereg: $apiError")
                }
            }
        }
    }
}

data class OrganisasjonsNavnOgAdresse(val organisasjonsnummer: String, val navn: String, val adresse: String)

@Serializable
data class Organisasjon(val organisasjonsnummer: String, val navn: Navn, val adresse: Adresse) {
    fun tilOrganisasjonsNavnOgAdresse() =
        OrganisasjonsNavnOgAdresse(organisasjonsnummer = organisasjonsnummer, navn = navn.sammensattnavn, adresse = adresse.toString())
}

@Serializable data class Navn(val sammensattnavn: String)

@Serializable
data class Adresse(
    val adresselinje1: String,
    val adresselinje2: String? = null,
    val adresselinje3: String? = null,
    val postnummer: String,
    val poststed: String,
) {
    override fun toString(): String {
        val adresser = listOfNotNull(adresselinje1, adresselinje2, adresselinje3).filter { it.isNotBlank() }
        return adresser.joinToString(", ") + ", $postnummer $poststed"
    }
}
