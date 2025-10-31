package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.toDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.plugins.di.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.util.*
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.encodeToByteString
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.auth.EntraId

class RapportServiceTest :
    FunSpec({
        context("RapportService") {
            val container = TestContainer.postgres

            test("kan lagre en rapport i databasen") {
                TestUtil.withFullApplication(container) {
                    TestUtil.loadDataSet("db/RapportServiceTest/simple.sql", container.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val ulagret =
                        UlagretRapport(OrgNr("39487569"), RapportType.K27, "K27 for Ulende Gås 2023-07-14", LocalDate.of(2023, 7, 14))
                    val rapport = sut.insert(ulagret)
                    rapport.id shouldBe Rapport.Id(2)
                    rapport.orgNr shouldBe ulagret.orgNr
                    rapport.type shouldBe ulagret.type
                }
            }

            test("kan hente en gitt rapport fra databasen") {
                TestUtil.withFullApplication(container) {
                    TestUtil.loadDataSet("db/RapportServiceTest/simple.sql", container.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val rapport = sut.findById(Rapport.Id(1))
                    rapport shouldNotBe null
                    rapport!!.id shouldBe Rapport.Id(1)
                    rapport.orgNr shouldBe OrgNr("123456789")
                    rapport.type shouldBe RapportType.K27
                }
            }

            test("kan hente en liste med rapporter for en organisasjon") {
                TestUtil.withFullApplication(container) {
                    TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", container.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val orgNr = OrgNr("123456789")
                    val rapporter = sut.listForOrg(orgNr)
                    rapporter.size shouldBe 2

                    rapporter[0].id shouldBe Rapport.Id(1)
                    rapporter[0].orgNr shouldBe orgNr
                    rapporter[0].type shouldBe RapportType.K27

                    rapporter[1].id shouldBe Rapport.Id(2)
                    rapporter[1].orgNr shouldBe orgNr
                    rapporter[1].type shouldBe RapportType.T14
                }
            }

            test("kan liste variantene av en rapport") {
                TestUtil.withFullApplication(container) {
                    TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", container.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val varianter = sut.listVariants(Rapport.Id(2))
                    varianter shouldNotBe null
                    varianter.size shouldBe 2

                    varianter[0].id shouldBe Variant.Id(3)
                    varianter[0].rapportId shouldBe Rapport.Id(2)
                    varianter[0].format shouldBe VariantFormat.Pdf
                    varianter[0].filnavn shouldBe "123456789_T14_2024-11-01.pdf"
                    varianter[0].bytes shouldBe 5

                    varianter[1].id shouldBe Variant.Id(4)
                    varianter[1].rapportId shouldBe Rapport.Id(2)
                    varianter[1].format shouldBe VariantFormat.Csv
                    varianter[1].filnavn shouldBe "123456789_T14_2024-11-01.csv"
                    varianter[1].bytes shouldBe 5
                }
            }

            test("kan lagre en rapport-variant i databasen") {
                TestUtil.withFullApplication(container) {
                    TestUtil.loadDataSet("db/RapportServiceTest/simple.sql", container.toDataSource())

                    val sut: RapportService = application.dependencies.resolve()
                    val ulagretRapport =
                        UlagretRapport(OrgNr("39487569"), RapportType.K27, "K27 for Ulende Gås 2023-07-14", LocalDate.of(2023, 7, 14))
                    val rapport = sut.insert(ulagretRapport)

                    val innhold = UUID.randomUUID().toString().encodeToByteString()
                    val ulagretVariant = UlagretVariant(rapport.id, VariantFormat.Pdf, rapport.filnavn(VariantFormat.Pdf), innhold)

                    val variant = sut.insertVariant(ulagretVariant)
                    variant.id shouldBe Variant.Id(3)
                    variant.rapportId shouldBe rapport.id
                    variant.format shouldBe VariantFormat.Pdf
                    variant.filnavn shouldBe "39487569_K27_2023-07-14.pdf"
                    variant.bytes shouldBe 36
                }
            }

            test("kan hente innholdet fra en variant") {
                val mockedRepository = mockk<RapportRepository>()
                TestUtil.withFullApplication(container, { dependencies.provide<RapportRepository> { mockedRepository } }) {
                    TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", container.toDataSource())
                    every { mockedRepository.hentInnhold(any(), any(), any()) } answers { callOriginal() }
                    every { mockedRepository.audit(any(), any()) } answers { callOriginal() }
                    val sut: RapportService = application.dependencies.resolve()

                    val innhold =
                        sut.hentInnhold(EntraId("navIdent", listOf("group")), Rapport.Id(4), VariantFormat.Pdf) { _, data -> data }

                    val expected =
                        buildByteString {
                                append("PDF".toByteArray())
                                append(0.toByte())
                                append("4".toByteArray())
                            }
                            .toByteArray()
                    innhold shouldBe expected

                    val auditSlot = slot<RapportAudit>()
                    verify(exactly = 1) { mockedRepository.audit(any(), capture(auditSlot)) }
                    val audit = auditSlot.captured
                    audit.rapportId shouldBe Rapport.Id(4)
                    audit.variantId shouldBe Variant.Id(7)
                    audit.hendelse shouldBe RapportAudit.Hendelse.VARIANT_NEDLASTET
                }
            }

            test("vil ikke audit-logge forsøk på å hente innholdet fra en variant hvis innholdet ikke returneres til klienten") {
                val mockedRepository = mockk<RapportRepository>()
                TestUtil.withFullApplication(container) {
                    TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", container.toDataSource())
                    every { mockedRepository.hentInnhold(any(), any(), any()) } answers { callOriginal() }
                    val sut: RapportService = application.dependencies.resolve()

                    sut.hentInnhold(EntraId("navIdent", listOf("group")), Rapport.Id(4), VariantFormat.Pdf) { _, _ -> null }

                    verify(exactly = 0) { mockedRepository.audit(any(), any()) }
                }
            }
        }
    })
