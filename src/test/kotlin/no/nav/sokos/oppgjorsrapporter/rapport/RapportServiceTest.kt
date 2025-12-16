package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.toDataSource
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.date.before
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldInclude
import io.ktor.server.plugins.di.*
import io.micrometer.core.instrument.Tags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.*
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import kotlinx.coroutines.async
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.encodeToByteString
import kotliquery.sessionOf
import kotliquery.using
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.util.heltAarDateRange
import no.nav.sokos.oppgjorsrapporter.utils.TestData

class RapportServiceTest :
    FunSpec({
        context("RapportService") {
            val dbContainer = TestContainer.postgres

            test("kan lagre en rapport-bestilling i databasen") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val bestilling =
                        sut.lagreBestilling("MQ: refusjon-bestilling-queue", RapportType.`ref-arbg`, "verbatim TextMessage contents")
                    bestilling.id shouldBe RapportBestilling.Id(2)
                    bestilling.mottatt shouldBe before(Instant.now())
                    bestilling.mottattFra shouldBe "MQ: refusjon-bestilling-queue"
                    bestilling.dokument shouldBe "verbatim TextMessage contents"
                    bestilling.genererSom shouldBe RapportType.`ref-arbg`
                    bestilling.ferdigProsessert shouldBe null
                }
            }

            test("kan telle antall uprosesserte rapport-bestillinger") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val tags = Tags.of("rapporttype", "ref-arbg", "kilde", "test", "feilet", "false")
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe null

                    val dokument = RefusjonsRapportBestilling.json.encodeToString(TestData.createRefusjonsRapportBestilling())
                    val _ = sut.lagreBestilling("test", RapportType.`ref-arbg`, dokument)
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 1
                }
            }

            test("kan prosessere en rapport-bestilling") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val dokument = RefusjonsRapportBestilling.json.encodeToString(TestData.createRefusjonsRapportBestilling())
                    val bestilling = sut.lagreBestilling("test", RapportType.`ref-arbg`, dokument)
                    val tags = Tags.of("rapporttype", "ref-arbg", "kilde", "test", "feilet", "false")
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 1

                    val prosessertId = sut.prosesserBestilling { _, bestilling -> bestilling.id }

                    prosessertId?.getOrThrow() shouldBe bestilling.id
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 0
                }
            }

            test("prosessering av en rapport-bestilling som feiler vil ikke etterlate rester i databasen") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val grunnlag = TestData.createRefusjonsRapportBestilling()
                    val dokument = RefusjonsRapportBestilling.json.encodeToString(grunnlag)
                    val _ = sut.lagreBestilling("test", RapportType.`ref-arbg`, dokument)
                    val tags = Tags.of("rapporttype", "ref-arbg", "kilde", "test", "feilet", "false")
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 1

                    var tilbakeRulletRapport: Rapport? = null
                    val resultat =
                        sut.prosesserBestilling { tx, bestilling ->
                            tilbakeRulletRapport =
                                sut.lagreRapport(
                                    tx,
                                    UlagretRapport(
                                        bestillingId = bestilling.id,
                                        orgnr = OrgNr(grunnlag.header.orgnr),
                                        type = bestilling.genererSom,
                                        datoValutert = grunnlag.header.valutert,
                                        bankkonto = Bankkonto(grunnlag.header.bankkonto),
                                        antallRader = grunnlag.datarec.size,
                                        antallUnderenheter = grunnlag.datarec.distinctBy { it.bedriftsnummer }.size,
                                        antallPersoner = grunnlag.datarec.distinctBy { it.fnr }.size,
                                    ),
                                )
                            throw IllegalStateException("Noe feilet")
                        }

                    resultat.shouldNotBeNull()
                    resultat shouldBeFailure { it.message shouldInclude "Noe feilet" }

                    tilbakeRulletRapport.shouldNotBeNull()
                    sut.finnRapport(tilbakeRulletRapport.id) shouldBe null

                    val metrikkerEtter = sut.metrikkForUprosesserteBestillinger()
                    metrikkerEtter.find { it.first == tags }?.second shouldBe 0
                    metrikkerEtter
                        .find { it.first == Tags.of("rapporttype", "ref-arbg", "kilde", "test", "feilet", "true") }
                        ?.second shouldBe 1
                }
            }

            test("prøver ikke å prosessere en rapport-bestilling som allerede er under prosessering") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val dokument = RefusjonsRapportBestilling.json.encodeToString(TestData.createRefusjonsRapportBestilling())
                    val bestilling = sut.lagreBestilling("test", RapportType.`ref-arbg`, dokument)
                    val tags = Tags.of("rapporttype", "ref-arbg", "kilde", "test", "feilet", "false")
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 1

                    val laasTattLatch = CountDownLatch(1)
                    val prosesseringKanStarteLatch = CountDownLatch(1)
                    val prosesseringFerdigLatch = CountDownLatch(1)

                    val prosessertId = async {
                        sut.prosesserBestilling { _, bestilling ->
                                laasTattLatch.countDown()
                                prosesseringKanStarteLatch.await()
                                bestilling.id
                            }
                            .also { prosesseringFerdigLatch.countDown() }
                    }

                    laasTattLatch.await()
                    sut.prosesserBestilling { _, bestilling -> bestilling.id } shouldBe null
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 1

                    prosesseringKanStarteLatch.countDown()
                    prosesseringFerdigLatch.await()

                    prosessertId.await()?.getOrThrow() shouldBe bestilling.id
                    sut.metrikkForUprosesserteBestillinger().find { it.first == tags }?.second shouldBe 0
                }
            }

            test("kan lagre en rapport i databasen") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val ulagret =
                        UlagretRapport(
                            bestillingId = RapportBestilling.Id(1),
                            orgnr = OrgNr("39487569"),
                            type = RapportType.`ref-arbg`,
                            datoValutert = LocalDate.of(2023, 7, 14),
                            bankkonto = Bankkonto("53785238218"),
                            antallRader = 3,
                            antallUnderenheter = 1,
                            antallPersoner = 2,
                        )
                    val rapport =
                        using(sessionOf(application.dependencies.resolve<DataSource>())) {
                            it.transaction { tx -> sut.lagreRapport(tx, ulagret) }
                        }
                    rapport.id shouldBe Rapport.Id(2)
                    rapport.orgnr shouldBe ulagret.orgnr
                    rapport.type shouldBe ulagret.type
                    rapport.antallRader shouldBe 3
                    rapport.antallUnderenheter shouldBe 1
                    rapport.antallPersoner shouldBe 2

                    val auditLog = sut.hentAuditLog(RapportAuditKriterier(rapport.id))
                    auditLog shouldHaveSize (2)
                    auditLog.map { it.hendelse } shouldContainInOrder
                        listOf(RapportAudit.Hendelse.RAPPORT_BESTILLING_MOTTATT, RapportAudit.Hendelse.RAPPORT_OPPRETTET)
                    auditLog.map { it.brukernavn }.toSet().single() shouldBe "system"
                }
            }

            test("kan hente en gitt rapport fra databasen") {
                TestUtil.withFullApplication(dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val rapport = sut.finnRapport(Rapport.Id(1))
                    rapport shouldNotBe null
                    rapport!!.id shouldBe Rapport.Id(1)
                    rapport.orgnr shouldBe OrgNr("123456789")
                    rapport.type shouldBe RapportType.`ref-arbg`
                }
            }

            test("kan hente en liste med rapporter for en organisasjon") {
                TestUtil.withFullApplication(dbContainer) {
                    TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val orgnr = OrgNr("123456789")
                    val rapporter =
                        sut.listRapporter(InkluderOrgKriterier(setOf(orgnr), RapportType.entries.toSet(), heltAarDateRange(2023), false))
                    rapporter.size shouldBe 2

                    rapporter[0].id shouldBe Rapport.Id(1)
                    rapporter[0].orgnr shouldBe orgnr
                    rapporter[0].type shouldBe RapportType.`ref-arbg`

                    rapporter[1].id shouldBe Rapport.Id(2)
                    rapporter[1].orgnr shouldBe orgnr
                    rapporter[1].type shouldBe RapportType.`trekk-kred`
                }
            }

            test("kan arkivere og de-arkivere en rapport i databasen") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val bruker = EntraId("enBruker", listOf("group"))
                    val rapportId = Rapport.Id(1)

                    val arkivert = sut.markerRapportArkivert(bruker, rapportId, { true }, true).shouldNotBeNull()
                    arkivert.id shouldBe rapportId
                    arkivert.erArkivert shouldBe true

                    val arkivertLog = sut.hentAuditLog(RapportAuditKriterier(rapportId)).single()
                    arkivertLog.hendelse shouldBe RapportAudit.Hendelse.RAPPORT_ARKIVERT
                    arkivertLog.brukernavn shouldBe "azure:NAVident=enBruker"

                    val dearkivert = sut.markerRapportArkivert(bruker, rapportId, { true }, false).shouldNotBeNull()
                    dearkivert.id shouldBe rapportId
                    dearkivert.erArkivert shouldBe false

                    val dearkivertLog = sut.hentAuditLog(RapportAuditKriterier(rapportId)).filterNot { it == arkivertLog }.single()
                    dearkivertLog.hendelse shouldBe RapportAudit.Hendelse.RAPPORT_DEARKIVERT
                    dearkivertLog.brukernavn shouldBe "azure:NAVident=enBruker"
                }
            }

            test("vil ikke audit-logge forsøk på å de-arkivere en ikke-arkivert rapport") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val bruker = EntraId("navIdent", listOf("group"))
                    val rapportId = Rapport.Id(1)

                    val dearkivert = sut.markerRapportArkivert(bruker, rapportId, { true }, false).shouldNotBeNull()
                    dearkivert.id shouldBe rapportId
                    dearkivert.erArkivert shouldBe false

                    val dearkivertLog = sut.hentAuditLog(RapportAuditKriterier(rapportId))
                    dearkivertLog shouldBe emptyList()
                }
            }

            test("kan liste variantene av en rapport") {
                TestUtil.withFullApplication(dbContainer) {
                    TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val varianter = sut.listVarianter(Rapport.Id(2))
                    varianter shouldNotBe null
                    varianter.size shouldBe 2

                    varianter[0].id shouldBe Variant.Id(3)
                    varianter[0].rapportId shouldBe Rapport.Id(2)
                    varianter[0].format shouldBe VariantFormat.Pdf
                    varianter[0].filnavn shouldBe "123456789_trekk-kred_2023-01-01.pdf"
                    varianter[0].bytes shouldBe 5

                    varianter[1].id shouldBe Variant.Id(4)
                    varianter[1].rapportId shouldBe Rapport.Id(2)
                    varianter[1].format shouldBe VariantFormat.Csv
                    varianter[1].filnavn shouldBe "123456789_trekk-kred_2023-01-01.csv"
                    varianter[1].bytes shouldBe 5
                }
            }

            test("kan lagre en rapport-variant i databasen") {
                TestUtil.withFullApplication(dbContainer) {
                    TestUtil.loadDataSet("db/simple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val ulagretRapport =
                        UlagretRapport(
                            bestillingId = RapportBestilling.Id(1),
                            orgnr = OrgNr("39487569"),
                            type = RapportType.`ref-arbg`,
                            datoValutert = LocalDate.of(2023, 7, 14),
                            bankkonto = Bankkonto("53785238218"),
                            antallRader = 3,
                            antallUnderenheter = 1,
                            antallPersoner = 2,
                        )
                    val variant =
                        using(sessionOf(application.dependencies.resolve<DataSource>())) {
                            it.transaction { tx ->
                                val rapport = sut.lagreRapport(tx, ulagretRapport)

                                val innhold = UUID.randomUUID().toString().encodeToByteString()
                                val ulagretVariant =
                                    UlagretVariant(rapport.id, VariantFormat.Pdf, rapport.filnavn(VariantFormat.Pdf), innhold)

                                val variant = sut.lagreVariant(tx, ulagretVariant)
                                variant.id shouldBe Variant.Id(3)
                                variant.rapportId shouldBe rapport.id
                                variant.format shouldBe VariantFormat.Pdf
                                variant.filnavn shouldBe "39487569_ref-arbg_2023-07-14.pdf"
                                variant.bytes shouldBe 36
                                variant
                            }
                        }

                    val auditLog = sut.hentAuditLog(RapportAuditKriterier(variant.rapportId, variantId = variant.id)).single()
                    auditLog.hendelse shouldBe RapportAudit.Hendelse.VARIANT_OPPRETTET
                    auditLog.brukernavn shouldBe "system"
                }
            }

            test("kan hente innholdet fra en variant") {
                TestUtil.withFullApplication(dbContainer = dbContainer) {
                    TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val rapportId = Rapport.Id(4)
                    val innhold =
                        sut.hentInnhold(
                            bruker = EntraId("navIdent", listOf("group")),
                            rapportId = rapportId,
                            format = VariantFormat.Pdf,
                            harTilgang = { true },
                        ) { _, data ->
                            data
                        }

                    val expected = buildByteString {
                        append("PDF".encodeToByteString())
                        append(0.toByte())
                        append("4".encodeToByteString())
                    }
                    innhold shouldBe expected

                    val auditLog = sut.hentAuditLog(RapportAuditKriterier(rapportId)).single()
                    auditLog.rapportId shouldBe rapportId
                    auditLog.variantId shouldBe Variant.Id(7)
                    auditLog.hendelse shouldBe RapportAudit.Hendelse.VARIANT_NEDLASTET
                    auditLog.brukernavn shouldBe "azure:NAVident=navIdent"
                }
            }

            test("vil ikke audit-logge forsøk på å hente innholdet fra en variant hvis innholdet ikke returneres til klienten") {
                val mockedRepository = mockk<RapportRepository>()
                TestUtil.withFullApplication(dbContainer) {
                    TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
                    every { mockedRepository.hentInnhold(any(), any(), any()) } answers { callOriginal() }
                    val sut: RapportService = application.dependencies.resolve()

                    val _ =
                        sut.hentInnhold(
                            bruker = EntraId("navIdent", listOf("group")),
                            rapportId = Rapport.Id(4),
                            format = VariantFormat.Pdf,
                            harTilgang = { false },
                        ) { _, _ ->
                        }

                    verify(exactly = 0) { mockedRepository.audit(any(), any()) }
                }
            }
        }
    })
