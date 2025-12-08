package no.nav.sokos.oppgjorsrapporter.innhold.generator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import org.slf4j.MDC

const val MANGLENDE_ORGANISASJONSNAVN = "-"
const val MANGLENDE_ORGANISASJONSADRESSE = "-"

class EregService(private val baseUrl: URI, val client: HttpClient, private val metrics: Metrics) {
    private val logger = KotlinLogging.logger {}

    fun hentOrganisasjonsNavnOgAdresse(orgnr: String): OrganisasjonsNavnOgAdresse {
        return runBlocking {
            logger.info("Henter organisasjonsnavn og adresse for $orgnr fra Ereg.")
            val eregUrl = baseUrl.resolve("/v2/organisasjon/$orgnr/noekkelinfo").toURL()
            val response = client.get(eregUrl) { header("Nav-Call-Id", MDC.get("x-correlation-id")) }
            metrics.tellEksternEndepunktRequest(eregUrl.path, "${response.status.value}")

            when {
                response.status.isSuccess() -> {
                    val organisasjonInfo = response.body<Organisasjon>().tilOrganisasjonsNavnOgAdresse()
                    logger.info("Fant organisasjon navn {} med orgnr {} i Ereg", organisasjonInfo, orgnr)
                    organisasjonInfo
                }

                else -> {
                    val apiError =
                        ApiError(
                            Clock.systemUTC().instant(),
                            response.status.value,
                            response.status.description,
                            response.errorMessage() ?: "Noe gikk galt ved oppslag mot Ereg-tjenesten",
                            eregUrl.path,
                        )
                    logger.error("Feil ved oppslag mot Ereg {}", apiError)
                    // TODO ønsker vi å generere pdf selv om oppslag mot Ereg feiler?
                    OrganisasjonsNavnOgAdresse(
                        organisasjonsnummer = orgnr,
                        navn = MANGLENDE_ORGANISASJONSNAVN,
                        adresse = MANGLENDE_ORGANISASJONSADRESSE,
                    )
                }
            }
        }
    }
}

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
