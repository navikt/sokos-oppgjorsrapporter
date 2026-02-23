package no.nav.sokos.oppgjorsrapporter.ereg

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import java.net.URI
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.generator.ApiError
import no.nav.sokos.oppgjorsrapporter.rapport.generator.eregErrorMessage
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import no.nav.sokos.utils.OrgNr
import org.slf4j.MDC

class EregService(private val baseUrl: URI, private val client: HttpClient, private val metrics: Metrics) {
    private val logger = KotlinLogging.logger {}

    suspend fun hentNoekkelInfo(orgnr: OrgNr): Organisasjon {
        logger.info { "Henter nøkkelinfo for $orgnr fra Ereg." }
        val eregUrl = baseUrl.resolve("/v2/organisasjon/${orgnr.raw}/noekkelinfo").toURL()
        val response = client.get(eregUrl) { header("Nav-Call-Id", MDC.get("x-correlation-id")) }
        metrics.tellEksternEndepunktRequest(response, "/v2/organisasjon/{orgnr}/noekkelinfo")

        return when {
            response.status.isSuccess() -> {
                response.body<Organisasjon>().also { logger.info { "Fant nøkkelinfo fra Ereg: $it" } }
            }
            else -> {
                val apiError = ApiError(response, response.eregErrorMessage() ?: "Noe gikk galt ved oppslag mot Ereg-tjenesten")
                logger.warn { "Feil ved oppslag mot Ereg: $apiError" }
                throw RuntimeException("Feil ved oppslag mot Ereg: $apiError")
            }
        }
    }

    suspend fun hentOrganisasjonsNavnOgAdresse(orgnr: OrgNr): OrganisasjonsNavnOgAdresse =
        hentNoekkelInfo(orgnr).tilOrganisasjonsNavnOgAdresse().also { logger.info { "Fant organisasjonsnavn fra Ereg: $it" } }
}

object EregHttpClientSetup : HttpClientSetup {
    override val jsonConfig: Json = commonJsonConfig
}

data class OrganisasjonsNavnOgAdresse(val organisasjonsnummer: String, val navn: String, val adresse: String)

@Serializable
data class Organisasjon(
    val organisasjonsnummer: String,
    val navn: Navn,
    val adresse: Adresse? = null,
    @Serializable(with = LocalDateAsStringSerializer::class) val opphoersdato: LocalDate? = null,
) {
    private fun formatterAdresse(): String =
        adresse?.let { adr ->
            with(adr) {
                listOfNotNull(
                        adresselinje1,
                        adresselinje2,
                        adresselinje3,
                        listOfNotNull(postnummer, poststed).filter { it.isNotBlank() }.joinToString(" "),
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            }
        } ?: "Ingen adresse"

    fun tilOrganisasjonsNavnOgAdresse() =
        OrganisasjonsNavnOgAdresse(organisasjonsnummer = organisasjonsnummer, navn = navn.sammensattnavn, adresse = formatterAdresse())
}

@Serializable data class Navn(val sammensattnavn: String)

@Serializable
data class Adresse(
    val adresselinje1: String? = null,
    val adresselinje2: String? = null,
    val adresselinje3: String? = null,
    val postnummer: String? = null,
    val poststed: String? = null,
)
