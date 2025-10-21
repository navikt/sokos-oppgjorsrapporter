package no.nav.sokos.oppgjorsrapporter.auth

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.TestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import no.nav.helsearbeidsgiver.utils.json.fromJson
import no.nav.helsearbeidsgiver.utils.test.wrapper.genererGyldig
import no.nav.helsearbeidsgiver.utils.wrapper.Orgnr
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.configureTestApplication
import no.nav.sokos.oppgjorsrapporter.module
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.medId
import no.nav.sokos.oppgjorsrapporter.rapport.medOrgNr
import no.nav.sokos.oppgjorsrapporter.utils.TestData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApiTest {
    val orgnrUtenPdpTilgang = OrgNr(Orgnr.genererGyldig().verdi)
    val hovedenhetOrgnrMedPdpTilgang = OrgNr(Orgnr.genererGyldig().verdi)
    val underenhetOrgnrMedPdpTilgang = OrgNr(Orgnr.genererGyldig().verdi)

    val mockedRapportService = mockk<RapportService>()
    val mockedPdpService = mockk<PdpService>()

    val mockOAuth2Server: MockOAuth2Server = MockOAuth2Server().apply { start() }
    val container = TestContainer.postgres
    private val testApplication: TestApplication = TestApplication {
        configureTestApplication(container, mockOAuth2Server)

        application {
            dependencies.provide<RapportService> { mockedRapportService }
            dependencies.provide<PdpService> { mockedPdpService }
            module()
        }
    }
    val client: HttpClient = testApplication.createClient { install(ContentNegotiation) { json() } }

    @AfterAll
    fun shutdownStuff() {
        runBlocking { testApplication.stop() }
        mockOAuth2Server.shutdown()
    }

    fun mockPdpTilganger() {
        every { mockedPdpService.harTilgang(systembruker = any(), orgnumre = any(), ressurs = any()) } returns false

        every {
            mockedPdpService.harTilgang(
                systembruker = match { it.orgNr == hovedenhetOrgnrMedPdpTilgang },
                orgnumre = match { it.contains(hovedenhetOrgnrMedPdpTilgang) || it.contains(underenhetOrgnrMedPdpTilgang) },
                ressurs = any(),
            )
        } returns true

        every {
            mockedPdpService.harTilgang(
                systembruker = match { it.orgNr == underenhetOrgnrMedPdpTilgang },
                orgnumre = match { it.contains(underenhetOrgnrMedPdpTilgang) },
                ressurs = any(),
            )
        } returns true
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HentApiAuthTest : ApiTest() {
    @BeforeEach
    fun setup() {
        mockPdpTilganger()
    }

    @AfterAll
    fun tearDown() {
        unmockkAll()
    }

    fun mockRapport(id: Long, orgnr: OrgNr): Rapport = TestData.rapportMock.medId(id).medOrgNr(orgnr)

    fun mockHentingAvEnkelRapport(id: Long, resultat: Rapport) {
        every { mockedRapportService.findById(Rapport.Id(id)) } returns resultat
    }

    @Test
    fun `gir 200 OK ved henting av metainfo om en spesifikk rapport som systembruker har tilgang til`() {
        val rapportId = 123L
        val rapport = mockRapport(id = rapportId, orgnr = underenhetOrgnrMedPdpTilgang)

        mockHentingAvEnkelRapport(rapportId, rapport)

        runBlocking {
            val respons =
                client.get(urlString = "/api/rapport/v1/$rapportId") {
                    bearerAuth(mockOAuth2Server.gyldigSystembrukerAuthToken(hovedenhetOrgnrMedPdpTilgang))
                }

            respons.status shouldBe HttpStatusCode.OK
            val rapport = respons.bodyAsText().fromJson(Rapport.serializer())
            rapport.orgNr shouldBe underenhetOrgnrMedPdpTilgang
        }
    }

    @Test
    fun `gir 404 Not Found ved henting av metainfo om en spesifikk rapport som systembruker ikke har tilgang til`() {
        val rapportId = 123L
        val rapport = mockRapport(id = rapportId, orgnr = underenhetOrgnrMedPdpTilgang)

        mockHentingAvEnkelRapport(rapportId, rapport)

        runBlocking {
            val respons =
                client.get(urlString = "/api/rapport/v1/$rapportId") {
                    bearerAuth(mockOAuth2Server.gyldigSystembrukerAuthToken(orgnrUtenPdpTilgang))
                }

            respons.status shouldBe HttpStatusCode.NotFound
        }
    }
}
