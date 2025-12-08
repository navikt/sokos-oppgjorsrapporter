package no.nav.sokos.oppgjorsrapporter.innhold.generator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import io.mockk.spyk
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.utils.eregResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EregServiceTest {

    @Test
    fun `hentOrganisasjonsNavnOgAdresse bør returnere en korekt formatert organisasjon navn og addresse`() {
        val mockMetrics = mockk<Metrics>(relaxed = true)

        // Arrange
        val orgnr = "990983666"
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(eregResponse),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val mockHttpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json(commonJsonConfig) } }
        val eregUrl = java.net.URI("http://dummy-ereg-url")
        val eregService = spyk(EregService(eregUrl, mockHttpClient, mockMetrics), recordPrivateCalls = true)
        val actual = eregService.hentOrganisasjonsNavnOgAdresse(orgnr)
        val expected =
            OrganisasjonsNavnOgAdresse(
                organisasjonsnummer = "990983666",
                navn = "NAV FAMILIE- OG PENSJONSYTELSER OSL",
                adresse = "Sannergata 2, 0557 Oslo",
            )

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `hentOrganisasjonsNavnOgAdresse bør håndtere feil respons fra Ereg tjenesten`() {
        val mockMetrics = mockk<Metrics>(relaxed = true)
        // Arrange
        val orgnr = "990983666"
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("""{"melding": "Noe gikk galt"}"""),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val mockHttpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json(commonJsonConfig) } }
        val eregUrl = java.net.URI("http://dummy-ereg-url")
        val eregService = spyk(EregService(eregUrl, mockHttpClient, mockMetrics), recordPrivateCalls = true)
        val actual = eregService.hentOrganisasjonsNavnOgAdresse(orgnr)

        val expected =
            OrganisasjonsNavnOgAdresse(
                organisasjonsnummer = "990983666",
                navn = MANGLENDE_ORGANISASJONSNAVN,
                adresse = MANGLENDE_ORGANISASJONSADRESSE,
            )
        // Assert
        assertEquals(expected, actual)
    }
}
