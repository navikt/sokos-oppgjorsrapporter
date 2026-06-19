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
                    javaClass.classLoader.getResourceAsStream("mq/trekk_kred_bestilling_flere_enheter.xml")!!
                )

            val forventet =
                listOf(
                        "H;20260217;MOLDE KOMMUNE;20260217;921221967; ;42141415926;80000815306;20260201;20260228",
                        "E;235753327;8020;;Molde barnevern;000000003;15267807496;Dolly DUCK MED VENNER;20260201;20260228;10000;KU",
                        "E;235753327;8020;;Molde barnevern;000000002;15267807496;DeDaDe DaDeDa;20260201;20260228;17500;KU",
                        "D;27500",
                        "E;235753327;4819;;Molde barnevern;000000001;15267807496;DONALD DUCK MED VENNER;20260201;20260228;15000;KU",
                        "D;15000",
                        "A;42500",
                        "E;235750560;8020;NAV Økonomi Stønad;Barnevernstjenesten;895821802;19486545678;JJJJJCCCCCBBB AAAAAAAAAAA;20260201;20260228;-20000;KU",
                        "E;235750560;8020;NAV Økonomi Stønad;Frivillig forvaltning, H. Berg;895821802;29667789766;BURGER MED POMMES FRITES;20260201;20260228;25000;KU",
                        "D;5000",
                        "E;235750560;4819;;Molde barnevern;000000004;15267807496;Mr DUCK;20260201;20260228;37500;KU",
                        "D;37500",
                        "A;42500",
                        "T;85000",
                    )
                    .joinToString("\r\n") + "\r\n"
            val faktisk = bestilling.toCsv()

            println("Versjon 1 med sumlinjer etter alle enheter")
            println("------------------------------------------")
            println(faktisk)

            assertThat(faktisk).isEqualTo(forventet)
        }
    })
