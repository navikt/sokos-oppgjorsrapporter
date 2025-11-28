package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.toDataSource
import io.kotest.inspectors.forExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import java.time.LocalDate
import java.time.Period
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
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
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()
                    val bestilling = TestData.createRefusjonsRapportBestilling()
                    rapportService.lagreBestilling("test", RapportType.K27, RefusjonsRapportBestilling.json.encodeToString((bestilling)))

                    val sut: BestillingProsessor = application.dependencies.resolve()

                    val rapport = sut.prosesserEnBestilling()!!
                    val varianter = rapportService.listVarianter(rapport.id)
                    varianter.size shouldBeGreaterThanOrEqual 1
                    // TODO: Oppdatere test til å verifisere at PDF-variant er på plass når vi har laget det
                    varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv)
                }
            }

            test("jobben for bestillings-prosessering plukker automatisk opp uprosessesert bestillinger og prosesserer dem") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapportService: RapportService = application.dependencies.resolve()
                    val kriterier =
                        EkskluderOrgKriterier(
                            ekskluderte = emptySet(),
                            rapportTyper = RapportType.entries.toSet(),
                            periode = LocalDateRange.of(LocalDate.now().minusDays(1), Period.ofDays(2)),
                            inkluderArkiverte = false,
                        )
                    val before = rapportService.listRapporter(kriterier)

                    val bestilling1 = TestData.createRefusjonsRapportBestilling(headerOrgnr = "123456789", headerValutert = LocalDate.now())
                    rapportService.lagreBestilling("test", RapportType.K27, RefusjonsRapportBestilling.json.encodeToString((bestilling1)))
                    val bestilling2 = TestData.createRefusjonsRapportBestilling(headerOrgnr = "987654321", headerValutert = LocalDate.now())
                    rapportService.lagreBestilling("test", RapportType.K27, RefusjonsRapportBestilling.json.encodeToString((bestilling2)))

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
                                varianter.map { it.format }.toSet() shouldBe setOf(VariantFormat.Csv)
                            }
                            nye.forExactly(1) { it.orgNr.raw shouldBe bestilling1.header.orgnr }
                            nye.forExactly(1) { it.orgNr.raw shouldBe bestilling2.header.orgnr }
                        }
                    } finally {
                        applicationState.disableBackgroundJobs = preDisabled
                    }
                }
            }
        }
    })
