package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotliquery.sessionOf
import kotliquery.using
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.TestUtil.EntraIdGroup
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.gyldigSystembrukerAuthToken
import no.nav.sokos.oppgjorsrapporter.auth.gyldigTokenXAuthToken
import no.nav.sokos.oppgjorsrapporter.auth.tokenFromDefaultProvider
import no.nav.sokos.oppgjorsrapporter.entraid.InternTilgangService
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselRepository
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.TilgangsmaskinService
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.UnexpectedResponseException
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.kontrakter.AvvisningskodeDTO
import no.nav.sokos.oppgjorsrapporter.toDataSource
import no.nav.sokos.oppgjorsrapporter.utils.TestData
import no.nav.sokos.oppgjorsrapporter.withTestApplication
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import no.nav.sokos.utils.genererGyldig
import org.threeten.extra.Minutes
import org.threeten.extra.MutableClock

class FrontendApiTest :
    FunSpec({
        context("Internt frontend-API") {
            val dbContainer = TestContainer.postgres

            context("for å liste varsler man har gitt opp å få sendt") {
                test("gir feilmelding uten autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val response = client.get("/api/rapport/frontend/oppgitt-varsling")
                            response.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }

                test("gir feilmelding uten EntraID-autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val respSystembruker =
                                client.get("/api/rapport/frontend/oppgitt-varsling") {
                                    bearerAuth(this@withMockOAuth2Server.gyldigSystembrukerAuthToken(OrgNr.genererGyldig().somUvalidert()))
                                }
                            respSystembruker.status shouldBe HttpStatusCode.Unauthorized

                            val respTokenx =
                                client.get("/api/rapport/frontend/oppgitt-varsling") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3")
                                    )
                                }
                            respTokenx.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }

                test("svarer med en tom liste hvis det ikke finnes noen oppgitte varsler") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                            val response =
                                client.get("/api/rapport/frontend/oppgitt-varsling") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.RANDOM_GROUP))
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText()).isEqualTo("[]")
                        }
                    }
                }

                test("svarer med en revers-kronologisk sortert liste hvis det finnes oppgitte varsler") {
                    val baseInstant = Instant.parse("2026-03-01T00:00:00.00Z")
                    val clock = MutableClock.of(baseInstant, ZoneOffset.UTC)
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = { dependencies.provide<Clock> { clock } }) {
                            TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())

                            val varselService: VarselService = application.dependencies.resolve()
                            val varselRepository: VarselRepository = application.dependencies.resolve()

                            // Registrer og gi opp varselbehov for rapportene på en slik måte at revers-kronologisk sortering gir
                            // forskjellig resultat for "rapport opprettet", "varsel registrert", og "varsel oppgitt"
                            val varselOpprettetOgOppgitt =
                                mapOf(
                                    Rapport.Id(1) to (Minutes.of(10) to Minutes.of(15)),
                                    Rapport.Id(2) to (Minutes.of(5) to Minutes.of(20)),
                                    Rapport.Id(3) to (Minutes.of(7) to Minutes.of(18)),
                                )
                            varselOpprettetOgOppgitt.keys.forEach { rapportId ->
                                val (varselRegistrert, varselOppgitt) = varselOpprettetOgOppgitt[rapportId]!!

                                clock.setInstant(baseInstant.plus(varselRegistrert))
                                varselService.registrerVarsel(rapportId)

                                clock.setInstant(baseInstant.plus(varselOppgitt))
                                val _ =
                                    using(sessionOf(application.dependencies.resolve<DataSource>())) {
                                        it.transaction { tx ->
                                            val varsel = varselRepository.finnUprosessertVarsel(tx, Instant.now(clock))
                                            checkNotNull(varsel) { "Fant ikke varsel for $rapportId" }
                                            varselRepository.oppdater(tx, varsel.copy(oppgitt = Instant.now(clock)))
                                        }
                                    }
                            }

                            val response =
                                client.get("/api/rapport/frontend/oppgitt-varsling") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.RANDOM_GROUP))
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText())
                                .isEqualTo(
                                    """
                                    [
                                        {
                                            "varselOpprettet": "2026-03-01T00:07:00Z",
                                            "varslingOppgitt": "2026-03-01T00:18:00Z",
                                            "rapport": {
                                                "id": 3,
                                                "orgnr": "234567890",
                                                "orgNavn": "Test Organisasjon B",
                                                "type": "ref-arbg",
                                                "datoValutert": "2023-11-01",
                                                "bankkonto": "23456789012",
                                                "opprettet": "2023-11-01T10:57:21Z",
                                                "arkivert": false
                                            }
                                        },
                                        {
                                            "varselOpprettet": "2026-03-01T00:05:00Z",
                                            "varslingOppgitt": "2026-03-01T00:20:00Z",
                                            "rapport": {
                                                "id": 2,
                                                "orgnr": "123456789",
                                                "orgNavn": "Test Organisasjon A",
                                                "type": "trekk-kred",
                                                "datoValutert": "2023-01-01",
                                                "bankkonto": "12345678901",
                                                "opprettet": "2023-01-01T08:37:52Z",
                                                "arkivert": false
                                            }
                                        },
                                        {
                                            "varselOpprettet": "2026-03-01T00:10:00Z",
                                            "varslingOppgitt": "2026-03-01T00:15:00Z",
                                            "rapport": {
                                                "id": 1,
                                                "orgnr": "123456789",
                                                "orgNavn": "Test Organisasjon A",
                                                "type": "ref-arbg",
                                                "datoValutert": "2023-01-01",
                                                "bankkonto": "12345678901",
                                                "opprettet": "2022-12-31T23:45:15Z",
                                                "arkivert": false
                                            }
                                        }
                                    ]
                                    """
                                        .trimIndent()
                                )
                        }
                    }
                }
            }

            context("for å hente en rapports audit-trail") {
                test("gir feilmelding uten autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val response = client.get("/api/rapport/frontend/1/audit")
                            response.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }

                test("gir feilmelding uten EntraID-autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val respSystembruker =
                                client.get("/api/rapport/frontend/1/audit") {
                                    bearerAuth(this@withMockOAuth2Server.gyldigSystembrukerAuthToken(OrgNr.genererGyldig().somUvalidert()))
                                }
                            respSystembruker.status shouldBe HttpStatusCode.Unauthorized

                            val respTokenx =
                                client.get("/api/rapport/frontend/1/audit") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3")
                                    )
                                }
                            respTokenx.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }

                test("gir feilmelding dersom den angitte rapport-IDen ikke finnes") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
                            val NON_EXISTENT_ID = 4711
                            val response =
                                client.get("/api/rapport/frontend/$NON_EXISTENT_ID/audit") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.RANDOM_GROUP))
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.NotFound
                        }
                    }
                }

                test("svarer med en kronologisk sortert liste med audit-events") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
                            val response =
                                client.get("/api/rapport/frontend/2/audit") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.RANDOM_GROUP))
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText())
                                .isEqualTo(
                                    """
                                    [
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-27T18:37:52Z",
                                            "hendelse": "RAPPORT_BESTILLING_MOTTATT",
                                            "format": null,
                                            "brukernavn": "system",
                                            "tekst": null
                                        },
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-27T18:38:12Z",
                                            "hendelse": "RAPPORT_OPPRETTET",
                                            "format": null,
                                            "brukernavn": "system",
                                            "tekst": null
                                        },
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-27T18:38:12Z",
                                            "hendelse": "VARIANT_OPPRETTET",
                                            "format": "text/csv",
                                            "brukernavn": "system",
                                            "tekst": null
                                        },
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-27T18:38:14Z",
                                            "hendelse": "VARIANT_OPPRETTET",
                                            "format": "application/pdf",
                                            "brukernavn": "system",
                                            "tekst": null
                                        },
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-27T18:41:37Z",
                                            "hendelse": "RAPPORT_VARSEL_SENDT",
                                            "format": null,
                                            "brukernavn": "system",
                                            "tekst": "Opprettet dialog med id 53ef33ca-4e23-11f1-b333-26e50d98e038"
                                        },
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-27T18:43:02Z",
                                            "hendelse": "VARIANT_NEDLASTET",
                                            "format": "text/csv",
                                            "brukernavn": "systembruker:system=registrert-system userOrg=123456789 id=6db27da6-4e25-11f1-81c5-26e50d98e038",
                                            "tekst": null
                                        },
                                        {
                                            "rapportId": 2,
                                            "tidspunkt": "2026-04-28T08:10:11Z",
                                            "hendelse": "VARIANT_NEDLASTET",
                                            "format": "application/pdf",
                                            "brukernavn": "entraid:NAVident=X735284",
                                            "tekst": null
                                        }
                                    ]
                                    """
                                        .trimIndent()
                                )
                        }
                    }
                }
            }

            context("for å hente rapporttyper en intern bruker har tilgang til") {
                val mockedInternTilgangService =
                    mockk<InternTilgangService> {
                        every { rapportTyperBrukerHarTilgangTil(bruker = any()) } returns emptySet()
                        every {
                            rapportTyperBrukerHarTilgangTil(bruker = EntraId(groups = listOf(EntraIdGroup.ADMIN), navIdent = "user"))
                        } returns RapportType.entries.toSet()
                        every {
                            rapportTyperBrukerHarTilgangTil(
                                bruker = EntraId(groups = listOf(EntraIdGroup.REF_ARBG, EntraIdGroup.TREKK_HEND), navIdent = "user")
                            )
                        } returns setOf(RapportType.`ref-arbg`, RapportType.`trekk-hend`)
                    }

                val dependencyOverrides: Application.() -> Unit = {
                    dependencies.provide<InternTilgangService> { mockedInternTilgangService }
                }

                test("gir feilmelding uten autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val response = client.get("/api/rapport/frontend/tilgang")
                            response.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }
                test("gir feilmelding uten EntraID-autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val respSystembruker =
                                client.get("/api/rapport/frontend/tilgang") {
                                    bearerAuth(this@withMockOAuth2Server.gyldigSystembrukerAuthToken(OrgNr.genererGyldig().somUvalidert()))
                                }
                            respSystembruker.status shouldBe HttpStatusCode.Unauthorized

                            val respTokenx =
                                client.get("/api/rapport/frontend/tilgang") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3")
                                    )
                                }
                            respTokenx.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }
                test("svarer med en liste av alle rapporttyper når intern bruker har admin tilgang") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val respForBrukerMedAdminTilgang =
                                client.get("/api/rapport/frontend/tilgang") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.ADMIN))
                                        )
                                    )
                                }
                            respForBrukerMedAdminTilgang.status shouldBe HttpStatusCode.OK
                            assertThatJson(respForBrukerMedAdminTilgang.bodyAsText())
                                .isEqualTo(
                                    """
                                    ["ref-arbg", "trekk-hend", "trekk-kred"]
                                    """
                                        .trimIndent()
                                )
                        }
                    }
                }
                test("svarer med rapporttyper intern bruker har tilgang til") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val respForBrukerMedRefArbgTilgang =
                                client.get("/api/rapport/frontend/tilgang") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims =
                                                mapOf(
                                                    "NAVident" to "user",
                                                    "groups" to listOf(EntraIdGroup.REF_ARBG, EntraIdGroup.TREKK_HEND),
                                                )
                                        )
                                    )
                                }
                            respForBrukerMedRefArbgTilgang.status shouldBe HttpStatusCode.OK
                            assertThatJson(respForBrukerMedRefArbgTilgang.bodyAsText())
                                .isEqualTo(
                                    """
                                    ["ref-arbg", "trekk-hend"]
                                    """
                                        .trimIndent()
                                )
                        }
                    }
                }
            }

            context("for å søke etter rapporter der fnr eller underenhet er nevnt") {
                val mockedTilgangsmaskinService = mockk<TilgangsmaskinService>()
                val nevntFnr = Fnr.genererGyldig().somUvalidert()
                val ukjentFnr = Fnr.genererGyldig().somUvalidert()
                val nevntUnderenhet = OrgNr.genererGyldig().somUvalidert()
                val ukjentUnderenhet = OrgNr.genererGyldig().somUvalidert()

                val mockRapport = TestData.rapportMock
                val mockedInternTilgangService =
                    mockk<InternTilgangService> {
                        every { harTilgangTilRessurs(bruker = any(), any(), any()) } returns false
                        every {
                            harTilgangTilRessurs(
                                bruker = EntraId(groups = listOf(EntraIdGroup.REF_ARBG), navIdent = "user"),
                                any(),
                                RapportType.`ref-arbg`,
                            )
                        } returns true
                        every { rapportTyperBrukerHarTilgangTil(bruker = any()) } returns emptySet()
                        every {
                            rapportTyperBrukerHarTilgangTil(bruker = EntraId(groups = listOf(EntraIdGroup.REF_ARBG), navIdent = "user"))
                        } returns setOf(RapportType.`ref-arbg`)
                    }

                val mockedRapportService =
                    mockk<RapportService> {
                        every { rapportSoek(nevntFnr, any(), any(), RapportType.`ref-arbg`) } returns listOf(mockRapport)
                        every { rapportSoek(ukjentFnr, any(), any(), RapportType.`ref-arbg`) } returns emptyList()
                        every { rapportSoek(nevntUnderenhet, any(), any(), RapportType.`ref-arbg`) } returns listOf(mockRapport)
                        every { rapportSoek(ukjentUnderenhet, any(), any(), RapportType.`ref-arbg`) } returns emptyList()
                    }

                val dependencyOverrides: Application.() -> Unit = {
                    dependencies.provide<RapportService> { mockedRapportService }
                    dependencies.provide<InternTilgangService> { mockedInternTilgangService }
                    dependencies.provide<TilgangsmaskinService> { mockedTilgangsmaskinService }
                }

                val clientJsonConfig = Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                }

                test("gir feilmelding uten autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val response = client.post("/api/rapport/frontend/nevnt-sok") { contentType(ContentType.Application.Json) }
                            response.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }

                test("gir feilmelding uten EntraID-autentisering") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer) {
                            val respSystembruker =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(this@withMockOAuth2Server.gyldigSystembrukerAuthToken(OrgNr.genererGyldig().somUvalidert()))
                                }
                            respSystembruker.status shouldBe HttpStatusCode.Unauthorized

                            val respTokenx =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3")
                                    )
                                }
                            respTokenx.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }

                test("svarer med en liste av matchende rapporter når det søkes på et fnr") {
                    coEvery { mockedTilgangsmaskinService.sjekkTilgang(any(), any()) } returns null
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportFnrSoek(
                                            nevntFnr,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText())
                                .isEqualTo(
                                    """
                                    [
                                      {
                                        "id": 123,
                                        "orgnr": "810007982",
                                        "orgNavn": "Test Bedrift AS",
                                        "type": "ref-arbg",
                                        "datoValutert": "2024-11-01",
                                        "bankkonto": "12345678901",
                                        "opprettet": "2024-11-01T12:15:02Z",
                                        "arkivert": false
                                      }
                                    ]
                                    """
                                        .trimIndent()
                                )
                        }
                    }
                }

                test("svarer med en liste av matchende rapporter når det søkes på en underenhet") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportUnderenhetSoek(
                                            nevntUnderenhet,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText())
                                .isEqualTo(
                                    """
                                    [
                                      {
                                        "id": 123,
                                        "orgnr": "810007982",
                                        "orgNavn": "Test Bedrift AS",
                                        "type": "ref-arbg",
                                        "datoValutert": "2024-11-01",
                                        "bankkonto": "12345678901",
                                        "opprettet": "2024-11-01T12:15:02Z",
                                        "arkivert": false
                                      }
                                    ]
                                    """
                                        .trimIndent()
                                )
                        }
                    }
                }

                test("gir feilmelding når bruker ikke har tilgang til rapporttype") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.TREKK_KRED))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportFnrSoek(
                                            nevntFnr,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.Forbidden
                        }
                    }
                }

                test("svarer med en tom liste når det søkes på et ukjent fnr") {
                    coEvery { mockedTilgangsmaskinService.sjekkTilgang(any(), any()) } returns null
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportFnrSoek(
                                            ukjentFnr,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText()).isEqualTo("[]")
                        }
                    }
                }

                test("svarer med en tom liste når det søkes på en ukjent underenhet") {
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportUnderenhetSoek(
                                            ukjentUnderenhet,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.OK
                            assertThatJson(response.bodyAsText()).isEqualTo("[]")
                        }
                    }
                }

                test("gir feilmelding når bruker ikke har tilgang til personen med gitt fnr") {
                    coEvery { mockedTilgangsmaskinService.sjekkTilgang(any(), nevntFnr) } returns
                        TestData.createPersonDetailResponseDTO(
                            brukerIdent = nevntFnr,
                            title = AvvisningskodeDTO.AVVIST_STRENGT_FORTROLIG_ADRESSE,
                            begrunnelse = "Du har ikke tilgang til brukere med strengt fortrolig adresse",
                        )
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportFnrSoek(
                                            nevntFnr,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.Forbidden
                            response.bodyAsText() shouldBe "Du har ikke tilgang til brukere med strengt fortrolig adresse"
                        }
                    }
                }
                test("gir feilmelding når tilgangsmaskinen gir uventet respons") {
                    coEvery { mockedTilgangsmaskinService.sjekkTilgang(any(), nevntFnr) } throws
                        UnexpectedResponseException("Uventet svar fra tilgangsmaskinen")
                    withMockOAuth2Server {
                        withTestApplication(dbContainer, dependencyOverrides = dependencyOverrides) {
                            val client = createClient { install(ContentNegotiation) { json(clientJsonConfig) } }

                            val response =
                                client.post("/api/rapport/frontend/nevnt-sok") {
                                    bearerAuth(
                                        this@withMockOAuth2Server.tokenFromDefaultProvider(
                                            claims = mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.REF_ARBG))
                                        )
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        FrontendApi.RapportFnrSoek(
                                            nevntFnr,
                                            LocalDate.now().minusDays(1),
                                            LocalDate.now(),
                                            RapportType.`ref-arbg`,
                                        )
                                    )
                                }
                            response.status shouldBe HttpStatusCode.Forbidden
                            response.bodyAsText() shouldBe "Uventet svar fra tilgangsmaskinen"
                        }
                    }
                }
            }
        }
    })
