package no.nav.sokos.oppgjorsrapporter.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.TestApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import no.nav.helsearbeidsgiver.utils.json.fromJson
import no.nav.helsearbeidsgiver.utils.test.wrapper.genererGyldig
import no.nav.helsearbeidsgiver.utils.wrapper.Orgnr
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.configureTestApplicationEnvironment
import no.nav.sokos.oppgjorsrapporter.module
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.Api
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.Variant
import no.nav.sokos.oppgjorsrapporter.rapport.VariantFormat
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

    val mockedRapportRepository = mockk<RapportRepository>()
    val mockedPdpService = mockk<PdpService>()

    val mockOAuth2Server: MockOAuth2Server = MockOAuth2Server().apply { start() }
    val dbContainer = TestContainer.postgres
    private val testApplication: TestApplication = TestApplication {
        configureTestApplicationEnvironment(dbContainer = dbContainer, server = mockOAuth2Server)

        application {
            dependencies.provide<RapportRepository> { mockedRapportRepository }
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
        coEvery { mockedPdpService.harTilgang(systembruker = any(), orgnumre = any(), ressurs = any()) } returns false

        coEvery {
            mockedPdpService.harTilgang(
                systembruker = match { it.userOrg == hovedenhetOrgnrMedPdpTilgang },
                orgnumre = match { it.contains(hovedenhetOrgnrMedPdpTilgang) || it.contains(underenhetOrgnrMedPdpTilgang) },
                ressurs = any(),
            )
        } returns true

        coEvery {
            mockedPdpService.harTilgang(
                systembruker = match { it.userOrg == underenhetOrgnrMedPdpTilgang },
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

    fun mockHentingAvEnkelRapport(resultat: Rapport) {
        every { mockedRapportRepository.finnRapport(any(), resultat.id) } returns resultat
        every { mockedRapportRepository.hentInnhold(any(), resultat.id, VariantFormat.Pdf) } returns
            Pair(
                Variant(Variant.Id(1), resultat.id, VariantFormat.Pdf, "filnavn", TestData.minimalPdf.length.toLong()),
                TestData.minimalPdf.encodeToByteString(),
            )
        every { mockedRapportRepository.audit(any(), any()) } returns Unit
    }

    @Test
    fun `gir 200 OK ved henting av metainfo om en spesifikk rapport som systembruker har tilgang til`() = runTest {
        val rapportHovedenhet = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang)
        val rapportUnderenhet = mockRapport(id = 234, orgnr = underenhetOrgnrMedPdpTilgang)

        mockHentingAvEnkelRapport(rapportHovedenhet)
        mockHentingAvEnkelRapport(rapportUnderenhet)

        mapOf(
                // systembruker for hovedenhet skal få lov til å hente rapport på hovedenhet
                hovedenhetOrgnrMedPdpTilgang to Api.RapportDTO(rapportHovedenhet),
                // systembruker for hovedenhet skal få lov til å hente rapport på underenhet
                hovedenhetOrgnrMedPdpTilgang to Api.RapportDTO(rapportUnderenhet),
                // systembruker for underenhet skal få lov til å hente rapport på underenhet
                underenhetOrgnrMedPdpTilgang to Api.RapportDTO(rapportUnderenhet),
            )
            .forEach { (orgnr, rapport) ->
                val respons =
                    client.get(urlString = "/api/rapport/v1/${rapport.id.raw}") {
                        bearerAuth(mockOAuth2Server.gyldigSystembrukerAuthToken(orgnr))
                    }

                respons.status shouldBe HttpStatusCode.OK
                respons.bodyAsText().fromJson(Api.RapportDTO.serializer()) shouldBe rapport
            }
    }

    @Test
    fun `gir 200 OK ved henting av innhold i en spesifikk rapport som systembruker har tilgang til`() = runTest {
        val rapportHovedenhet = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang)
        val rapportUnderenhet = mockRapport(id = 234, orgnr = underenhetOrgnrMedPdpTilgang)

        mockHentingAvEnkelRapport(rapportHovedenhet)
        mockHentingAvEnkelRapport(rapportUnderenhet)

        mapOf(
                // systembruker for hovedenhet skal få lov til å hente rapport på hovedenhet
                hovedenhetOrgnrMedPdpTilgang to rapportHovedenhet,
                // systembruker for hovedenhet skal få lov til å hente rapport på underenhet
                hovedenhetOrgnrMedPdpTilgang to rapportUnderenhet,
                // systembruker for underenhet skal få lov til å hente rapport på underenhet
                underenhetOrgnrMedPdpTilgang to rapportUnderenhet,
            )
            .forEach { (orgnr, rapport) ->
                val respons =
                    client.get(urlString = "/api/rapport/v1/${rapport.id.raw}/innhold") {
                        bearerAuth(mockOAuth2Server.gyldigSystembrukerAuthToken(orgnr))
                        accept(ContentType.Application.Pdf)
                    }

                respons.status shouldBe HttpStatusCode.OK
                respons.bodyAsText() shouldStartWith "%PDF-"
            }
    }

    @Test
    fun `gir 404 Not Found ved henting av metainfo om en spesifikk rapport som systembruker ikke har tilgang til`() = runTest {
        val rapportMedTilgang = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang)
        val rapportUtenTilgang = mockRapport(id = 321, orgnr = orgnrUtenPdpTilgang)

        mockHentingAvEnkelRapport(rapportMedTilgang)
        mockHentingAvEnkelRapport(rapportUtenTilgang)

        mapOf(
                // systembruker for en org som ikke har gitt systembrukeren rettigheter forsøker å aksessere rapport for en annen org
                orgnrUtenPdpTilgang to rapportMedTilgang,
                // systembruker for en org som ikke har gitt systembrukeren rettigheter forsøker å aksessere rapport for "egen" org
                orgnrUtenPdpTilgang to rapportUtenTilgang,
                // systembruker for underenhet forsøker å aksessere rapport for egen orgs hovedenhet
                underenhetOrgnrMedPdpTilgang to rapportMedTilgang,
            )
            .forEach { (orgnr, rapport) ->
                val respons =
                    client.get(urlString = "/api/rapport/v1/${rapport.id.raw}") {
                        bearerAuth(mockOAuth2Server.gyldigSystembrukerAuthToken(orgnr))
                    }
                respons.status shouldBe HttpStatusCode.NotFound
            }
    }

    @Test
    fun `gir 404 Not Found ved henting av innhold i en spesifikk rapport som systembruker ikke har tilgang til`() = runTest {
        val rapportMedTilgang = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang)
        val rapportUtenTilgang = mockRapport(id = 321, orgnr = orgnrUtenPdpTilgang)

        mockHentingAvEnkelRapport(rapportMedTilgang)
        mockHentingAvEnkelRapport(rapportUtenTilgang)

        mapOf(
                // systembruker for en org som ikke har gitt systembrukeren rettigheter forsøker å aksessere rapport for en annen org
                orgnrUtenPdpTilgang to rapportMedTilgang,
                // systembruker for en org som ikke har gitt systembrukeren rettigheter forsøker å aksessere rapport for "egen" org
                orgnrUtenPdpTilgang to rapportUtenTilgang,
                // systembruker for underenhet forsøker å aksessere rapport for egen orgs hovedenhet
                underenhetOrgnrMedPdpTilgang to rapportMedTilgang,
            )
            .forEach { (orgnr, rapport) ->
                val respons =
                    client.get(urlString = "/api/rapport/v1/${rapport.id.raw}/innhold") {
                        bearerAuth(mockOAuth2Server.gyldigSystembrukerAuthToken(orgnr))
                        accept(ContentType.Application.Pdf)
                    }
                respons.status shouldBe HttpStatusCode.NotFound
            }
    }
}
