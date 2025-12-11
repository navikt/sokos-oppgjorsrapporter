package no.nav.sokos.oppgjorsrapporter.ereg

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
import java.net.URI
import kotlinx.coroutines.test.runTest
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.innhold.generator.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.utils.eregResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EregServiceTest {

    @Test
    fun `hentOrganisasjonsNavnOgAdresse bør returnere en korekt formatert organisasjon navn og addresse`() = runTest {
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
        val eregUrl = URI("http://dummy-ereg-url")
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
    fun `hentOrganisasjonsNavnOgAdresse bør kaste RuntimeException feil respons fra Ereg tjenesten`() = runTest {
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
        val eregUrl = URI("http://dummy-ereg-url")
        val eregService = spyk(EregService(eregUrl, mockHttpClient, mockMetrics), recordPrivateCalls = true)
        assertThrows<RuntimeException> { eregService.hentOrganisasjonsNavnOgAdresse(orgnr) }
    }
}
