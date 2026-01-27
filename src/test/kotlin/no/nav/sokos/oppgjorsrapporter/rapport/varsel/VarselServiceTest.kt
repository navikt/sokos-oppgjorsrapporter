package no.nav.sokos.oppgjorsrapporter.rapport.varsel

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.toDataSource
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotliquery.sessionOf
import kotliquery.using
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.dialogporten.DialogportenClient
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService

class VarselServiceTest :
    FunSpec({
        context("VarselService") {
            val dbContainer = TestContainer.postgres

            test("kan registrere behovet for et varsel i databasen") {
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = { dependencies { provide<Clock> { Clock.fixed(Instant.EPOCH, ZoneOffset.UTC) } } },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapport = application.dependencies.resolve<RapportService>().finnRapport(Rapport.Id(1))!!

                    val sut: VarselService = application.dependencies.resolve()
                    val repository: VarselRepository = application.dependencies.resolve()
                    using(sessionOf(application.dependencies.resolve<DataSource>())) {
                        it.transaction { tx ->
                            sut.registrerVarsel(tx, rapport)

                            // Verifiser at det er registrert et varsel som ser ut som forventet, og slett det
                            val varsel = repository.finnUprosessertVarsel(tx, Instant.EPOCH)!!
                            varsel shouldBe Varsel(Varsel.Id(1), rapport.id, VarselSystem.dialogporten, Instant.EPOCH, 0, Instant.EPOCH)
                            repository.slett(tx, varsel.id)

                            // Det skal ikke være registrert flere varsler
                            repository.finnUprosessertVarsel(tx, Instant.EPOCH) shouldBe null
                        }
                    }
                }
            }

            test("kan prosessere et varsel") {
                val repository = mockk<VarselRepository>(relaxed = true)
                val dialogportenClient = mockk<DialogportenClient>(relaxed = true)
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies {
                            provide { repository }
                            provide { dialogportenClient }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapport = application.dependencies.resolve<RapportService>().finnRapport(Rapport.Id(1))!!
                    val varsel = Varsel(Varsel.Id(1), rapport.id, VarselSystem.dialogporten, Instant.EPOCH, 0, Instant.EPOCH)
                    every { repository.finnUprosessertVarsel(any(), any()) }.returns(varsel)
                    val dialogUuid = UUID.randomUUID()
                    coEvery { dialogportenClient.opprettDialog(any()) }.returns(dialogUuid)

                    val sut: VarselService = application.dependencies.resolve()
                    using(sessionOf(application.dependencies.resolve<DataSource>())) {
                        it.transaction { tx ->
                            val res = sut.sendVarsel()!!.getOrThrow()

                            res shouldBe Pair(varsel, dialogUuid)
                        }
                    }
                    coVerify(ordering = Ordering.SEQUENCE) {
                        val _ = repository.finnUprosessertVarsel(any(), any())
                        val _ = dialogportenClient.opprettDialog(any())
                        repository.slett(any(), varsel.id)
                    }
                }
            }

            test("kan håndtere at prosessering av et varsel feiler") {
                val repository = mockk<VarselRepository>(relaxed = true)
                val dialogportenClient = mockk<DialogportenClient>(relaxed = true)
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    dependencyOverrides = {
                        dependencies {
                            provide { repository }
                            provide { dialogportenClient }
                        }
                    },
                ) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())
                    val rapport = application.dependencies.resolve<RapportService>().finnRapport(Rapport.Id(1))!!
                    val varsel = Varsel(Varsel.Id(1), rapport.id, VarselSystem.dialogporten, Instant.EPOCH, 0, Instant.EPOCH)
                    every { repository.finnUprosessertVarsel(any(), any()) }.returns(varsel)
                    coEvery { dialogportenClient.opprettDialog(any()) }.throws(RuntimeException("Dialogporten virker ikke nå"))
                    val varselSlot = slot<Varsel>()
                    every { repository.oppdater(any(), capture(varselSlot)) }.returns(1)

                    val sut: VarselService = application.dependencies.resolve()
                    using(sessionOf(application.dependencies.resolve<DataSource>())) {
                        it.transaction { tx ->
                            val res = sut.sendVarsel()!!

                            res.shouldBeFailure()
                        }
                    }

                    varselSlot.captured.opprettet shouldBe varsel.opprettet
                    varselSlot.captured.antallForsok shouldBe (varsel.antallForsok + 1)
                    varselSlot.captured.nesteForsok shouldBeGreaterThan varsel.nesteForsok

                    coVerify(ordering = Ordering.SEQUENCE) {
                        val _ = repository.finnUprosessertVarsel(any(), any())
                        val _ = dialogportenClient.opprettDialog(any())
                        val _ = repository.oppdater(any(), capture(varselSlot))
                    }
                }
            }
        }
    })
