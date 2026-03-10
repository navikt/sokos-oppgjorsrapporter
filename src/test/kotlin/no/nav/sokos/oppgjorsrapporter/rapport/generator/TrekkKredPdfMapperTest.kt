package no.nav.sokos.oppgjorsrapporter.rapport.generator

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
        test("Generer trekk-kred payload til pdfgen og valider felter") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    TestUtil.readFile("mq/trekk_kred_bestilling_flere_enheter.xml")
                )

            val onoa =
                OrganisasjonsNavnOgAdresse(organisasjonsnummer = "123456789", navn = "ONOG ONOG OH NO ONONOGO", adresse = "Onogata 33")
            val payload = bestilling.mapTilTrekkKredRapportPdfPayload(onoa, LocalDate.now())

            val jsonPayload = Json.encodeToString(payload)

            jsonPayload shouldBe TestUtil.readFile("pdfpayload/trekk_kred_flere_enheter_pdf_payload.json")
        }
    })
