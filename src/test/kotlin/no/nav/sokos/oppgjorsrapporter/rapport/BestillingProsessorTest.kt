package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.toDataSource
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.utils.TestData

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
        }
    })
