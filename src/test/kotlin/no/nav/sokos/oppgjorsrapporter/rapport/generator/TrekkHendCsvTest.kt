package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.mq.TrekkHendBestilling
import no.nav.sokos.oppgjorsrapporter.utils.xmlResourceAsString
import org.assertj.core.api.Assertions.assertThat

class TrekkHendCsvTest :
    FunSpec({
        test("Generert CSV for trekk-hend (kreditor) har riktig format") {
            val bestilling = TrekkHendBestilling.decode(xmlResourceAsString("mq/trekk_hend_bestilling-kreditor.xml"))

            val forventet =
                listOf(
                        "H;kreditor;21.04.2026;859503241;McDuck inkasso AS;Postboks 313;;;3158;ANDEBY;",
                        "T;087453421;NAMSFOGDEN I ANDEBY;0107036257420540624;18453866986;Donald Duck;Ingen ytelse;",
                        "T;141619942;NAMSFOGDEN I GÅSEBY;0103064541812328464;22468528375;Gulbrand Gråstein;Opphør ytelse;",
                    )
                    .joinToString("\r\n") + "\r\n"
            val faktisk = bestilling.toCsv()

            println("Versjon 1 med sumlinjer etter alle enheter")
            println("------------------------------------------")
            println(faktisk)

            assertThat(faktisk).isEqualTo(forventet)
        }

        test("Generert CSV for trekk-hend (namsmann) har riktig format") {
            val bestilling = TrekkHendBestilling.decode(xmlResourceAsString("mq/trekk_hend_bestilling-namsmann.xml"))

            val forventet =
                listOf(
                        "H;namsmann;21.04.2026;087453421;NAMSFOGDEN I ANDEBY;Postboks 313;;;3158;;",
                        "T;859503241;McDuck inkasso AS;0107036257420540624;18453866986;Donald Duck;Ingen ytelse;",
                        "T;134907843;RIKERUD COLLECTION AS;5900562964792591;24508924131;Guffen Gås;Ingen ytelse;",
                        "T;134907843;RIKERUD COLLECTION AS;01626785861606857982;28482019570;Petter Smart;Opphør ytelse;",
                    )
                    .joinToString("\r\n") + "\r\n"
            val faktisk = bestilling.toCsv()

            println("Versjon 1 med sumlinjer etter alle enheter")
            println("------------------------------------------")
            println(faktisk)

            assertThat(faktisk).isEqualTo(forventet)
        }
    })
