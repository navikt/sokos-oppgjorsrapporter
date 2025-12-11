package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.toDataSource
import io.kotest.inspectors.forExactly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import kotlinx.io.bytestring.ByteString
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.innhold.generator.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.innhold.generator.RefusjonArbeidsgiverInnholdGenerator
import no.nav.sokos.oppgjorsrapporter.innhold.generator.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.utils.TestData
import org.threeten.extra.LocalDateRange

class BestillingProsessorTest :
    FunSpec({
        context("BestillingProsessor") {
            val dbContainer = TestContainer.postgres

            test("gjør ingenting dersom det ikke finnes noen bestillinger i databasen") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.withEmptyDatabase(dbContainer.toDataSource()) {}

                    val sut: BestillingProsessor = application.dependencies.resolve()

                    sut.prosesserEnBestilling() shouldBe null
                }
            }

            test("gjør ingenting dersom det ikke finnes noen uprosesseserte bestillinger i databasen") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: BestillingProsessor = application.dependencies.resolve()

                    sut.prosesserEnBestilling() shouldBe null
                }
            }

            test("oppretter rapport + varianter når det finnes en uprosessesert bestilling i databasen") {
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies.provide<EregService> {
                            mockk<EregService>(relaxed = true) {
                                coEvery { hentOrganisasjonsNavnOgAdresse(any()) } returns
                                    OrganisasjonsNavnOgAdresse(
                                        navn = "Test Organisasjon",
                                        adresse = "Testveien 1, 0123 Oslo",
                                        organisasjonsnummer = "123456789",
                                    )
                            }
                        }
                        dependencies.provide<RefusjonArbeidsgiverInnholdGenerator> {
                            mockk<RefusjonArbeidsgiverInnholdGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any()) } returns ByteString("test pdf content".toByteArray())
                            }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()

                    val bestilling =
                        TestData.createRefusjonsRapportBestilling(
                            datarec =
                                (1..10).map { i -> TestData.createDataRec(bedriftsnummer = "12345678${i % 3}", fnr = "123456${i % 5}8901") }
                        )
                    rapportService.lagreBestilling(
                        "test",
                        RapportType.`ref-arbg`,
                        RefusjonsRapportBestilling.json.encodeToString((bestilling)),
                    )

                    val sut: BestillingProsessor = application.dependencies.resolve()

                    val rapport = sut.prosesserEnBestilling()!!
                    rapport.antallRader shouldBe 10
                    rapport.antallUnderenheter shouldBe 3
                    rapport.antallPersoner shouldBe 5

                    val varianter = rapportService.listVarianter(rapport.id)
                    varianter.size shouldBeExactly 2
                    varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv, VariantFormat.Pdf)
                }
            }

            test("jobben for bestillings-prosessering plukker automatisk opp uprosessesert bestillinger og prosesserer dem") {
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies.provide<Clock> { Clock.fixed(Instant.EPOCH, ZoneOffset.UTC) }
                        // Mock EregService to avoid external API calls
                        dependencies.provide<EregService> {
                            mockk<EregService>(relaxed = true) {
                                coEvery { hentOrganisasjonsNavnOgAdresse(any()) } returns
                                    OrganisasjonsNavnOgAdresse(
                                        navn = "Test Organisasjon",
                                        adresse = "Testveien 1, 0123 Oslo",
                                        organisasjonsnummer = "123456789",
                                    )
                            }
                        }
                        dependencies.provide<RefusjonArbeidsgiverInnholdGenerator> {
                            mockk<RefusjonArbeidsgiverInnholdGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any()) } returns ByteString("test pdf content".toByteArray())
                            }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()
                    val clock: Clock = application.dependencies.resolve()

                    val kriterier =
                        EkskluderOrgKriterier(
                            ekskluderte = emptySet(),
                            bankkonto = null,
                            rapportTyper = RapportType.entries.toSet(),
                            periode = LocalDateRange.of(LocalDate.now(clock).minusDays(1), Period.ofDays(2)),
                            inkluderArkiverte = false,
                        )
                    val before = rapportService.listRapporter(kriterier)

                    val bestilling1 =
                        TestData.createRefusjonsRapportBestilling(headerOrgnr = "123456789", headerValutert = LocalDate.now(clock))
                    rapportService.lagreBestilling(
                        "test",
                        RapportType.`ref-arbg`,
                        RefusjonsRapportBestilling.json.encodeToString((bestilling1)),
                    )
                    val bestilling2 =
                        TestData.createRefusjonsRapportBestilling(headerOrgnr = "987654321", headerValutert = LocalDate.now(clock))
                    rapportService.lagreBestilling(
                        "test",
                        RapportType.`ref-arbg`,
                        RefusjonsRapportBestilling.json.encodeToString((bestilling2)),
                    )

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    val preDisabled = applicationState.disableBackgroundJobs
                    try {
                        applicationState.disableBackgroundJobs = false

                        eventually {
                            val after = rapportService.listRapporter(kriterier)
                            (after.size - before.size) shouldBeGreaterThanOrEqual 2
                            val nye = after.filterNot { before.contains(it) }
                            nye.forEach { rapport ->
                                val varianter = rapportService.listVarianter(rapport.id)
                                varianter.size shouldBeGreaterThanOrEqual 1
                                // TODO: Oppdatere test til å verifisere at PDF-variant er på plass når vi har laget det
                                varianter.forExactly(1) {
                                    it.format shouldBe VariantFormat.Csv
                                    it.filnavn shouldBe "${rapport.orgnr.raw}_ref-arbg_1970-01-01_${rapport.id.raw}.csv"
                                }
                            }
                            nye.forExactly(1) { it.orgnr.raw shouldBe bestilling1.header.orgnr }
                            nye.forExactly(1) { it.orgnr.raw shouldBe bestilling2.header.orgnr }
                        }
                    } finally {
                        applicationState.disableBackgroundJobs = preDisabled
                    }
                }
            }

            test("jobben for bestillings-prosessering håndterer multiple rapporter med likt orgnr+dato") {
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies.provide<Clock> { Clock.fixed(Instant.EPOCH, ZoneOffset.UTC) }
                        // Mock EregService to avoid external API calls
                        dependencies.provide<EregService> {
                            mockk<EregService>(relaxed = true) {
                                coEvery { hentOrganisasjonsNavnOgAdresse(any()) } returns
                                    OrganisasjonsNavnOgAdresse(
                                        navn = "Test Organisasjon",
                                        adresse = "Testveien 1, 0123 Oslo",
                                        organisasjonsnummer = "123456789",
                                    )
                            }
                        }
                        dependencies.provide<RefusjonArbeidsgiverInnholdGenerator> {
                            mockk<RefusjonArbeidsgiverInnholdGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any()) } returns ByteString("test pdf content".toByteArray())
                            }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()
                    val kriterier =
                        EkskluderOrgKriterier(
                            ekskluderte = emptySet(),
                            bankkonto = null,
                            rapportTyper = RapportType.entries.toSet(),
                            periode = LocalDateRange.of(LocalDate.now().minusDays(1), Period.ofDays(2)),
                            inkluderArkiverte = false,
                        )
                    val before = rapportService.listRapporter(kriterier)

                    val bestilling1 = TestData.createRefusjonsRapportBestilling(headerOrgnr = "123456789", headerValutert = LocalDate.now())
                    rapportService.lagreBestilling(
                        "test",
                        RapportType.`ref-arbg`,
                        RefusjonsRapportBestilling.json.encodeToString((bestilling1)),
                    )
                    val bestilling2 =
                        bestilling1.copy(
                            header = bestilling1.header.let { it.copy(sumBelop = it.sumBelop * 2.toBigDecimal()) },
                            datarec = bestilling1.datarec.map { it.copy(belop = it.belop * 2.toBigDecimal()) },
                        )
                    rapportService.lagreBestilling(
                        "test",
                        RapportType.`ref-arbg`,
                        RefusjonsRapportBestilling.json.encodeToString((bestilling2)),
                    )

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    val preDisabled = applicationState.disableBackgroundJobs
                    try {
                        applicationState.disableBackgroundJobs = false

                        eventually {
                            val after = rapportService.listRapporter(kriterier)
                            (after.size - before.size) shouldBeGreaterThanOrEqual 2
                            val nye = after.filterNot { before.contains(it) }
                            val varianterMap =
                                nye.associate { rapport ->
                                    val varianter = rapportService.listVarianter(rapport.id)
                                    varianter.size shouldBeExactly 2
                                    // TODO: Oppdatere test til å verifisere at PDF-variant er på plass når vi har laget det
                                    varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv, VariantFormat.Pdf)
                                    rapport.id to varianter
                                }
                            varianterMap.values.flatten().map { it.filnavn }.toSet().size shouldBeGreaterThanOrEqual nye.size
                            nye.map { it.orgnr.raw }.toSet() shouldContainExactly setOf(bestilling1.header.orgnr, bestilling2.header.orgnr)
                        }
                    } finally {
                        applicationState.disableBackgroundJobs = preDisabled
                    }
                }
            }
        }
    })
