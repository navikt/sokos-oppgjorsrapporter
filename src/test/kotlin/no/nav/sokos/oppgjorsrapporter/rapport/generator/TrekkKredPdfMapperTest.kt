package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.serialization.json.Json
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredPdfMapper.mapTilTrekkKredRapportPdfPayload
import tools.jackson.module.kotlin.readValue

class TrekkKredPdfMapperTest :
    FunSpec({
        val fixedDate = LocalDate.of(2026, 3, 10)
        val onoa = OrganisasjonsNavnOgAdresse(organisasjonsnummer = "123456789", navn = "ONOG ONOG OH NO ONONOGO", adresse = "Onogata 33")

        test("Generer trekk-kred payload til pdfgen og valider felter") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    javaClass.classLoader.getResourceAsStream("mq/trekk_kred_bestilling_flere_enheter.xml")!!
                )

            val payload = bestilling.mapTilTrekkKredRapportPdfPayload(onoa, fixedDate)

            val jsonPayload = Json.encodeToString(payload)

            jsonPayload shouldBe TestUtil.readFile("pdfpayload/trekk_kred_flere_enheter_pdf_payload.json")
        }

        test("Når validering etter mapping feiler så skal det kastes exception") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    javaClass.classLoader.getResourceAsStream("mq/trekk_kred_bestilling_flere_enheter_med_feil_summering.xml")!!
                )

            shouldThrowWithMessage<Exception>("Validering av pdf payload for bestilling feilet") {
                bestilling.mapTilTrekkKredRapportPdfPayload(onoa, fixedDate)
            }
        }
    })
