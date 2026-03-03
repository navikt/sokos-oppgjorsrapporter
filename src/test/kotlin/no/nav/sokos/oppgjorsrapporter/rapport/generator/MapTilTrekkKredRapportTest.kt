package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.time.LocalDate
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredPdfMapper.mapTilTrekkKredRapportPdfPayload
import tools.jackson.module.kotlin.readValue

class MapTilTrekkKredRapportTest :
    FunSpec({
        test("Generer trekk-kred payload til pdfgen") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    TestUtil.readFile("mq/trekk_kred_bestilling_flere_enheter.xml")
                )

            val onoa =
                OrganisasjonsNavnOgAdresse(organisasjonsnummer = "123456789", navn = "ONOG ONOG OH NO ONONOGO", adresse = "Onogata 33")
            val payload = bestilling.mapTilTrekkKredRapportPdfPayload(onoa, LocalDate.now())

            // println(Json { prettyPrint = true }.encodeToString(payload))
            payload.shouldNotBeNull()
        }

        test("sjekker csv") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    TestUtil.readFile("mq/trekk_kred_bestilling.xml")
                )

            println("Versjon 1 med sumlinjer etter alle enheter")
            println("------------------------------------------")
            println(bestilling.toCsv_V1())
            println("\n")
            println("Versjon 2 med sumlinjer etter hver enhet og arkivreferanse")
            println("------------------------------------------")
            println(bestilling.toCsv_V2())
        }
    })
