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
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil.EntraIdGroup
import no.nav.sokos.oppgjorsrapporter.TestUtil.Orgnrs
import no.nav.sokos.oppgjorsrapporter.configureTestApplicationEnvironment
import no.nav.sokos.oppgjorsrapporter.entraid.InternTilgangService
import no.nav.sokos.oppgjorsrapporter.module
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.Api
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.rapport.Variant
import no.nav.sokos.oppgjorsrapporter.rapport.VariantFormat
import no.nav.sokos.oppgjorsrapporter.rapport.medId
import no.nav.sokos.oppgjorsrapporter.rapport.medOrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.medType
import no.nav.sokos.oppgjorsrapporter.utils.TestData
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import no.nav.sokos.utils.genererGyldig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApiTest {
    val orgnrUtenPdpTilgang = OrgNr.genererGyldig().somUvalidert()
    val hovedenhetOrgnrMedPdpTilgang = OrgNr.genererGyldig().somUvalidert()
    val underenhetOrgnrMedPdpTilgang = OrgNr.genererGyldig().somUvalidert()

    val pidUtenPdpTilgang = Fnr.genererGyldig().somUvalidert()
    val pidMedPdpTilgang = Fnr.genererGyldig().somUvalidert()

    val mockedRapportRepository = mockk<RapportRepository>()
    val mockedPdpService = mockk<PdpService>()
    val mockedInternTilgangService = mockk<InternTilgangService>()

    val mockOAuth2Server: MockOAuth2Server = MockOAuth2Server().apply { start() }
    val dbContainer = TestContainer.postgres
    private val testApplication: TestApplication = TestApplication {
        configureTestApplicationEnvironment(dbContainer = dbContainer, server = mockOAuth2Server)

        application {
            dependencies.provide<RapportRepository> { mockedRapportRepository }
            dependencies.provide<PdpService> { mockedPdpService }
            dependencies.provide<InternTilgangService> { mockedInternTilgangService }
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
        coEvery { mockedPdpService.harTilgang(tokenX = any(), orgnumre = any(), ressurs = any()) } returns false

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

        coEvery {
            mockedPdpService.harTilgang(
                tokenX = match { it.pid == pidMedPdpTilgang.raw },
                orgnumre = match { it.contains(hovedenhetOrgnrMedPdpTilgang) || it.contains(underenhetOrgnrMedPdpTilgang) },
                ressurs = any(),
            )
        } returns true
    }

    fun mockedInternTilganger() {
        every { mockedInternTilgangService.harTilgangTilRessurs(bruker = any(), Orgnrs.NAV_ORGNR, rapportType = any()) } returns false
        every { mockedInternTilgangService.harTilgangTilRessurs(bruker = any(), Orgnrs.IKKE_NAV_ORGNR, rapportType = any()) } returns false
        every {
            mockedInternTilgangService.harTilgangTilRessurs(
                bruker = EntraId(groups = listOf(EntraIdGroup.ADMIN), navIdent = "user"),
                orgnr = Orgnrs.NAV_ORGNR,
                rapportType = any(),
            )
        } returns true
        every {
            mockedInternTilgangService.harTilgangTilRessurs(
                bruker = EntraId(groups = listOf(EntraIdGroup.ADMIN), navIdent = "user"),
                orgnr = Orgnrs.IKKE_NAV_ORGNR,
                rapportType = any(),
            )
        } returns true
        every {
            mockedInternTilgangService.harTilgangTilRessurs(
                bruker = EntraId(groups = listOf(EntraIdGroup.REF_ARBG), navIdent = "user"),
                orgnr = Orgnrs.NAV_ORGNR,
                rapportType = RapportType.`ref-arbg`,
            )
        } returns false
        every {
            mockedInternTilgangService.harTilgangTilRessurs(
                bruker = EntraId(groups = listOf(EntraIdGroup.REF_ARBG), navIdent = "user"),
                orgnr = Orgnrs.IKKE_NAV_ORGNR,
                rapportType = RapportType.`ref-arbg`,
            )
        } returns true
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HentApiAuthTest : ApiTest() {
    @BeforeEach
    fun setup() {
        mockPdpTilganger()
        mockedInternTilganger()
    }

    @AfterAll
    fun tearDown() {
        unmockkAll()
    }

    fun mockRapport(id: Long, orgnr: OrgNr, type: RapportType): Rapport = TestData.rapportMock.medId(id).medOrgNr(orgnr).medType(type)

    fun mockHentingAvEnkelRapport(resultat: Rapport) {
        every { mockedRapportRepository.finnRapport(any(), resultat.id) } returns resultat
        every { mockedRapportRepository.hentInnhold(any(), resultat.id, VariantFormat.Pdf) } returns
            Pair(
                Variant(Variant.Id(1), resultat.id, VariantFormat.Pdf, "filnavn", TestData.minimalPdf.length.toLong()),
                TestData.minimalPdf.encodeToByteString(),
            )
        every { mockedRapportRepository.tidligereLastetNedAvEksternBruker(any(), any()) } returns false
        every { mockedRapportRepository.audit(any(), any()) } returns Unit
    }

    @Test
    fun `gir 200 OK ved henting av metainfo om en spesifikk rapport som systembruker har tilgang til`() = runTest {
        val rapportHovedenhet = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)
        val rapportUnderenhet = mockRapport(id = 234, orgnr = underenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)

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
        val rapportHovedenhet = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)
        val rapportUnderenhet = mockRapport(id = 234, orgnr = underenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)

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
        val rapportMedTilgang = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)
        val rapportUtenTilgang = mockRapport(id = 321, orgnr = orgnrUtenPdpTilgang, type = RapportType.`ref-arbg`)

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
        val rapportMedTilgang = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)
        val rapportUtenTilgang = mockRapport(id = 321, orgnr = orgnrUtenPdpTilgang, type = RapportType.`ref-arbg`)

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

    @Test
    fun `gir 200 OK ved henting av innhold i en spesifikk rapport som tokenX bruker har tilgang til`() = runTest {
        val rapport = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)

        mockHentingAvEnkelRapport(rapport)

        val respons =
            client.get(urlString = "/api/rapport/v1/${rapport.id.raw}/innhold") {
                bearerAuth(mockOAuth2Server.gyldigTokenXAuthToken(pid = pidMedPdpTilgang, acr = "Level3"))
                accept(ContentType.Application.Pdf)
            }

        respons.status shouldBe HttpStatusCode.OK
        respons.bodyAsText() shouldStartWith "%PDF-"
    }

    @Test
    fun `gir 404 OK ved henting av innhold i en spesifikk rapport som tokenX bruker har tilgang til`() = runTest {
        val rapport = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)

        mockHentingAvEnkelRapport(rapport)

        val respons =
            client.get(urlString = "/api/rapport/v1/${rapport.id.raw}/innhold") {
                bearerAuth(mockOAuth2Server.gyldigTokenXAuthToken(pid = pidUtenPdpTilgang, acr = "Level3"))
                accept(ContentType.Application.Pdf)
            }

        respons.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `gir 200 OK ved henting av metainfo om en spesifikk rapport som tokenX bruker har tilgang til`() = runTest {
        val rapport = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)

        mockHentingAvEnkelRapport(rapport)

        val respons =
            client.get(urlString = "/api/rapport/v1/${rapport.id.raw}") {
                bearerAuth(mockOAuth2Server.gyldigTokenXAuthToken(pid = pidMedPdpTilgang, acr = "Level3"))
            }

        respons.status shouldBe HttpStatusCode.OK
        respons.bodyAsText().fromJson(Api.RapportDTO.serializer()) shouldBe Api.RapportDTO(rapport)
    }

    @Test
    fun `gir 404 Not Found ved henting av metainfo om en spesifikk rapport som tokenX bruker ikke har tilgang til`() = runTest {
        val rapport = mockRapport(id = 123, orgnr = hovedenhetOrgnrMedPdpTilgang, type = RapportType.`ref-arbg`)

        mockHentingAvEnkelRapport(rapport)

        val respons =
            client.get(urlString = "/api/rapport/v1/${rapport.id.raw}") {
                bearerAuth(mockOAuth2Server.gyldigTokenXAuthToken(pid = pidUtenPdpTilgang, acr = "Level3"))
            }

        respons.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `gir 200 OK ved henting av metainfo om en spesifikk rapport for alle rapporttyper når intern bruker er admin`() = runTest {
        val refArbgRapportNav = mockRapport(id = 123, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`ref-arbg`)
        val refArbgRapportIkkeNav = mockRapport(id = 123, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`ref-arbg`)
        val trekkHendRapportNav = mockRapport(id = 456, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`trekk-hend`)
        val trekkHendRapportIkkeNav = mockRapport(id = 456, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`trekk-hend`)
        val trekkKredRapportNav = mockRapport(id = 789, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`trekk-kred`)
        val trekkKredRapportIkkeNav = mockRapport(id = 789, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`trekk-kred`)

        mockHentingAvEnkelRapport(refArbgRapportNav)
        mockHentingAvEnkelRapport(refArbgRapportIkkeNav)
        mockHentingAvEnkelRapport(trekkHendRapportNav)
        mockHentingAvEnkelRapport(trekkHendRapportIkkeNav)
        mockHentingAvEnkelRapport(trekkKredRapportNav)
        mockHentingAvEnkelRapport(trekkKredRapportIkkeNav)

        val refArbgRapportDtoNav = Api.RapportDTO(refArbgRapportNav)
        val refArbgRapportDtoIkkeNav = Api.RapportDTO(refArbgRapportIkkeNav)
        val trekkHendRapportDtoNav = Api.RapportDTO(trekkHendRapportNav)
        val trekkHendRapportDtoIkkeNav = Api.RapportDTO(trekkHendRapportIkkeNav)
        val trekkKredRapportDtoNav = Api.RapportDTO(trekkKredRapportNav)
        val trekkKredRapportDtoIkkeNav = Api.RapportDTO(trekkKredRapportIkkeNav)

        listOf(
                refArbgRapportDtoNav,
                refArbgRapportDtoIkkeNav,
                trekkHendRapportDtoNav,
                trekkHendRapportDtoIkkeNav,
                trekkKredRapportDtoNav,
                trekkKredRapportDtoIkkeNav,
            )
            .forEach { rapport ->
                val respons =
                    client.get(urlString = "/api/rapport/v1/${rapport.id.raw}") {
                        bearerAuth(
                            mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.ADMIN)))
                        )
                    }

                respons.status shouldBe HttpStatusCode.OK
                respons.bodyAsText().fromJson(Api.RapportDTO.serializer()) shouldBe rapport
            }
    }

    @Test
    fun `gir 200 OK ved henting av metainfo om en spesifikk rapport som ikke hører Nav til når intern bruker kun har rapporttype spesifikk tilgang`() =
        runTest {
            val refArbgRapportIkkeNav = mockRapport(id = 123, orgnr = Orgnrs.IKKE_NAV_ORGNR, type = RapportType.`ref-arbg`)

            mockHentingAvEnkelRapport(refArbgRapportIkkeNav)

            val refArbgRapportDtoIkkeNav = Api.RapportDTO(refArbgRapportIkkeNav)

            val responsIkkeNav =
                client.get(urlString = "/api/rapport/v1/${refArbgRapportDtoIkkeNav.id.raw}") {
                    bearerAuth(
                        mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG)))
                    )
                }

            responsIkkeNav.status shouldBe HttpStatusCode.OK
            responsIkkeNav.bodyAsText().fromJson(Api.RapportDTO.serializer()) shouldBe refArbgRapportDtoIkkeNav
        }

    @Test
    fun `gir 404 NOT FOUND ved henting av metainfo om en spesifikk rapport for Nav selv om intern bruker har rapporttype spesifikk tilgang`() =
        runTest {
            val refArbgRapportNav = mockRapport(id = 123, orgnr = Orgnrs.NAV_ORGNR, type = RapportType.`ref-arbg`)

            mockHentingAvEnkelRapport(refArbgRapportNav)

            val refArbgRapportDtoNav = Api.RapportDTO(refArbgRapportNav)

            val responsNav =
                client.get(urlString = "/api/rapport/v1/${refArbgRapportDtoNav.id.raw}") {
                    bearerAuth(
                        mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG)))
                    )
                }

            responsNav.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `gir 404 NOT FOUND ved henting av metainfo om en spesifikk rapport når intern bruker mangler riktig tilgang`() = runTest {
        val trekkKredRapport = mockRapport(id = 789, orgnr = Orgnrs.IKKE_NAV_ORGNR, type = RapportType.`trekk-kred`)

        mockHentingAvEnkelRapport(trekkKredRapport)

        val trekkKredRapportDto = Api.RapportDTO(trekkKredRapport)

        val respons =
            client.get(urlString = "/api/rapport/v1/${trekkKredRapportDto.id.raw}") {
                bearerAuth(
                    mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG)))
                )
            }

        respons.status shouldBe HttpStatusCode.NotFound
    }
}
