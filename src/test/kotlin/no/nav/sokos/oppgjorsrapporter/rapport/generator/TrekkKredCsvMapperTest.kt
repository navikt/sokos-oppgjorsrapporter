package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import tools.jackson.module.kotlin.readValue

class TrekkKredCsvMapperTest :
    FunSpec({
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
