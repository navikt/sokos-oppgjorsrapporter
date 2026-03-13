package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotliquery.sessionOf
import kotliquery.using
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.auth.gyldigSystembrukerAuthToken
import no.nav.sokos.oppgjorsrapporter.auth.gyldigTokenXAuthToken
import no.nav.sokos.oppgjorsrapporter.auth.tokenFromDefaultProvider
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselRepository
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService
import no.nav.sokos.oppgjorsrapporter.toDataSource
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
                                            mapOf("NAVident" to "user", "groups" to listOf("group"))
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
                                            mapOf("NAVident" to "user", "groups" to listOf("group"))
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
        }
    })
