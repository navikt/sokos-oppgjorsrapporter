package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forExactly
import io.kotest.matchers.collections.shouldContainExactly
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
import kotlin.random.Random
import kotlinx.io.bytestring.ByteString
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.RefusjonsRapportGenerator
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredRapportGenerator
import no.nav.sokos.oppgjorsrapporter.toDataSource
import no.nav.sokos.oppgjorsrapporter.utils.TestData
import no.nav.sokos.oppgjorsrapporter.withEnabledBakgrunnsJobb
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import no.nav.sokos.utils.genererGyldig
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
                                coEvery { hentOrganisasjonsNavnOgAdresse(any()) } answers
                                    {
                                        OrganisasjonsNavnOgAdresse(
                                            navn = "Test Organisasjon",
                                            adresse = "Testveien 1, 0123 Oslo",
                                            organisasjonsnummer = firstArg(),
                                        )
                                    }
                            }
                        }
                        dependencies.provide<RefusjonsRapportGenerator> {
                            mockk<RefusjonsRapportGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any(), any()) } returns ByteString("test pdf content".toByteArray())
                            }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()
                    val bestilling =
                        TestData.createRefusjonsRapportBestilling(
                            datarec =
                                (1..10).map { i ->
                                    val r3 = Random(i % 3)
                                    val r5 = Random(i % 5)
                                    TestData.createDataRec(bedriftsnummer = OrgNr.genererGyldig(r3), fnr = Fnr.genererGyldig(random = r5))
                                }
                        )
                    val _ =
                        rapportService.lagreBestilling(
                            "test",
                            RapportType.`ref-arbg`,
                            RefusjonsRapportBestilling.json.encodeToString((bestilling)),
                        )

                    val sut: BestillingProsessor = application.dependencies.resolve()

                    val rapport = sut.prosesserEnBestilling()!!.getOrThrow()
                    rapport.antallRader shouldBe 10
                    rapport.antallUnderenheter shouldBe 3
                    rapport.antallPersoner shouldBe 5

                    val varianter = rapportService.listVarianter(rapport.id)
                    varianter.size shouldBe 2
                    varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv, VariantFormat.Pdf)
                }
            }

            test("oppretter rapport + varianter når det finnes en uprosessesert trekk-kred bestilling i databasen") {
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
                        dependencies.provide<TrekkKredRapportGenerator> {
                            mockk<TrekkKredRapportGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any(), any()) } returns ByteString("test pdf content".toByteArray())
                            }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()
                    val _ =
                        rapportService.lagreBestilling(
                            "test",
                            RapportType.`trekk-kred`,
                            TestUtil.readFile("mq/trekk_kred_bestilling_flere_enheter.xml"),
                        )

                    val sut: BestillingProsessor = application.dependencies.resolve()

                    val rapport = sut.prosesserEnBestilling()!!.getOrThrow()
                    rapport.antallRader shouldBe 6
                    rapport.antallUnderenheter shouldBe null
                    rapport.antallPersoner shouldBe 3

                    val varianter = rapportService.listVarianter(rapport.id)
                    varianter.size shouldBe 2
                    varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv, VariantFormat.Pdf)
                }
            }

            test("jobben for bestillings-prosessering plukker automatisk opp uprosessesert bestillinger og prosesserer dem") {
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies.provide<Clock> { Clock.fixed(Instant.EPOCH, ZoneOffset.UTC) }
                        dependencies.provide<EregService> {
                            mockk<EregService>(relaxed = true) {
                                coEvery { hentOrganisasjonsNavnOgAdresse(any()) } answers
                                    {
                                        OrganisasjonsNavnOgAdresse(
                                            navn = "Test Organisasjon",
                                            adresse = "Testveien 1, 0123 Oslo",
                                            organisasjonsnummer = firstArg(),
                                        )
                                    }
                            }
                        }
                        dependencies.provide<RefusjonsRapportGenerator> {
                            mockk<RefusjonsRapportGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any(), any()) } returns ByteString("test pdf content".toByteArray())
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

                    val bestilling1 = TestData.createRefusjonsRapportBestilling(headerValutert = LocalDate.now(clock))
                    val _ =
                        rapportService.lagreBestilling(
                            "test",
                            RapportType.`ref-arbg`,
                            RefusjonsRapportBestilling.json.encodeToString((bestilling1)),
                        )
                    val bestilling2 = TestData.createRefusjonsRapportBestilling(headerValutert = LocalDate.now(clock))
                    val _ =
                        rapportService.lagreBestilling(
                            "test",
                            RapportType.`ref-arbg`,
                            RefusjonsRapportBestilling.json.encodeToString((bestilling2)),
                        )

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    applicationState.withEnabledBakgrunnsJobb<BestillingProsessor> {
                        eventually {
                            val after = rapportService.listRapporter(kriterier)
                            (after.size - before.size) shouldBeGreaterThanOrEqual 2
                            val nye = after.filterNot { before.contains(it) }
                            nye.forEach { rapport ->
                                val varianter = rapportService.listVarianter(rapport.id)
                                varianter.size shouldBe 2
                                varianter.forExactly(1) {
                                    it.format shouldBe VariantFormat.Csv
                                    it.filnavn shouldBe "${rapport.orgnr.raw}_ref-arbg_1970-01-01_${rapport.id.raw}.csv"
                                }
                                varianter.forExactly(1) {
                                    it.format shouldBe VariantFormat.Pdf
                                    it.filnavn shouldBe "${rapport.orgnr.raw}_ref-arbg_1970-01-01_${rapport.id.raw}.pdf"
                                }
                            }
                            nye.forExactly(1) { it.orgnr shouldBe bestilling1.header.orgnr }
                            nye.forExactly(1) { it.orgnr shouldBe bestilling2.header.orgnr }
                        }
                    }
                }
            }

            test("jobben for bestillings-prosessering håndterer multiple rapporter med likt orgnr+dato") {
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies.provide<Clock> { Clock.fixed(Instant.EPOCH, ZoneOffset.UTC) }
                        dependencies.provide<EregService> {
                            mockk<EregService>(relaxed = true) {
                                coEvery { hentOrganisasjonsNavnOgAdresse(any()) } answers
                                    {
                                        OrganisasjonsNavnOgAdresse(
                                            navn = "Test Organisasjon",
                                            adresse = "Testveien 1, 0123 Oslo",
                                            organisasjonsnummer = firstArg(),
                                        )
                                    }
                            }
                        }
                        dependencies.provide<RefusjonsRapportGenerator> {
                            mockk<RefusjonsRapportGenerator>(relaxed = true) {
                                coEvery { genererPdfInnhold(any(), any()) } returns ByteString("test pdf content".toByteArray())
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

                    val bestilling1 = TestData.createRefusjonsRapportBestilling(headerValutert = LocalDate.now())
                    val _ =
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
                    val _ =
                        rapportService.lagreBestilling(
                            "test",
                            RapportType.`ref-arbg`,
                            RefusjonsRapportBestilling.json.encodeToString((bestilling2)),
                        )

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    applicationState.withEnabledBakgrunnsJobb<BestillingProsessor> {
                        eventually {
                            val after = rapportService.listRapporter(kriterier)
                            (after.size - before.size) shouldBeGreaterThanOrEqual 2
                            val nye = after.filterNot { before.contains(it) }
                            val varianterMap =
                                nye.associate { rapport ->
                                    val varianter = rapportService.listVarianter(rapport.id)
                                    varianter.size shouldBe 2
                                    varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv, VariantFormat.Pdf)
                                    rapport.id to varianter
                                }
                            varianterMap.values.flatten().map { it.filnavn }.toSet().size shouldBeGreaterThanOrEqual nye.size
                            nye.map { it.orgnr }.toSet() shouldContainExactly setOf(bestilling1.header.orgnr, bestilling2.header.orgnr)
                        }
                    }
                }
            }
        }
    })
