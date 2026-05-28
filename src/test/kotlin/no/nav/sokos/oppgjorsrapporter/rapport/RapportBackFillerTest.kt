package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.ktor.server.plugins.di.dependencies
import java.time.LocalDate
import javax.sql.DataSource
import kotliquery.queryOf
import kotliquery.sessionOf
import kotliquery.using
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.toDataSource
import no.nav.sokos.oppgjorsrapporter.utils.TestData
import no.nav.sokos.oppgjorsrapporter.withEnabledBakgrunnsJobb
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import no.nav.sokos.utils.genererGyldig
import org.threeten.extra.LocalDateRange

class RapportBackFillerTest :
    FunSpec({
        context("RapportBackFiller") {
            val dbContainer = TestContainer.postgres

            test("fyller automatisk inn nevnt_info for rapporter som mangler dette") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val bedriftsNr = OrgNr.genererGyldig()
                    val fnrs =
                        buildSet {
                                while (size < 10) {
                                    add(Fnr.genererGyldig())
                                }
                            }
                            .toList()
                    val bestilling =
                        TestData.createRefusjonsRapportBestilling(
                            datarec = fnrs.map { fnr -> TestData.createDataRec(bedriftsnummer = bedriftsNr, fnr = fnr) }
                        )

                    using(sessionOf(application.dependencies.resolve<DataSource>())) { session ->
                        session.transaction { tx ->
                            val oppdatert =
                                queryOf(
                                        "UPDATE rapport.rapport_bestilling SET dokument = :dokument WHERE id = :id",
                                        mapOf("id" to 1, "dokument" to RefusjonsRapportBestilling.json.encodeToString((bestilling))),
                                    )
                                    .asUpdate
                                    .let { tx.run(it) }
                            oppdatert shouldBe 1
                        }
                    }

                    val rapportService: RapportService = application.dependencies.resolve()
                    fnrs.forAll { fnr ->
                        rapportService.rapportSoek(
                            fnr.somUvalidert(),
                            LocalDateRange.of(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)),
                            inkluderArkiverte = true,
                            rapportType = RapportType.`ref-arbg`,
                        ) should beEmpty()
                    }

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    applicationState.withEnabledBakgrunnsJobb<RapportBackFiller> {
                        eventually {
                            fnrs.forAll { fnr ->
                                rapportService.rapportSoek(
                                    fnr.somUvalidert(),
                                    LocalDateRange.of(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)),
                                    inkluderArkiverte = true,
                                    rapportType = RapportType.`ref-arbg`,
                                ) shouldNot beEmpty()
                            }
                        }
                    }
                }
            }
        }
    })
