package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import org.assertj.core.api.Assertions.assertThat
import tools.jackson.module.kotlin.readValue

class TrekkKredCsvMapperTest :
    FunSpec({
        test("Generert trekk-kred CSV har riktig format") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    javaClass.classLoader.getResourceAsStream("mq/trekk_kred_bestilling.xml")!!
                )

            val forventet =
                listOf(
                        "H;20260217;MOLDE KOMMUNE;20260217;921221967; ;42141415926;80000815306;20260201;20260228",
                        "E;235753327;8020;;Molde barnevern;;15267807496;DONALD DUCK MED VENNER;20260201;20260228;10000;KU",
                        "E;235750560;8020;NAV Økonomi Stønad;Barnevernstjenesten;895821802;19486545678;JJJJJCCCCCBBB AAAAAAAAAAA;20260201;20260228;7500;KU",
                        "E;235750560;8020;NAV Økonomi Stønad;Frivillig forvaltning, H. Berg;895821802;29667789766;BURGER MED POMMES FRITES;20260201;20260228;5000;KU",
                        "D;22500",
                        "A;22500",
                        "T;22500",
                    )
                    .joinToString("\r\n") + "\r\n"
            val faktisk = bestilling.toCsv()

            println("Versjon 1 med sumlinjer etter alle enheter")
            println("------------------------------------------")
            println(faktisk)

            assertThat(faktisk).isEqualTo(forventet)
        }
    })
