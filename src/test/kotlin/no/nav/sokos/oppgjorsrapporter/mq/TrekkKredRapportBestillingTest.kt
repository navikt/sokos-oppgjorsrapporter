package no.nav.sokos.oppgjorsrapporter.mq

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.utils.xmlResourceAsString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode

class TrekkKredRapportBestillingTest :
    FunSpec({
        test("lese inn xml") {
            assertThatCode {
                    val _ = TrekkKredRapportBestilling.decode(TestUtil.readFile("mq/trekk_kred_bestilling.xml"))
                }
                .doesNotThrowAnyException()
        }

        test("feile validering hvis bestillingen inneholder inkonsistent data") {
            val bestilling =
                TrekkKredRapportBestilling.decode(xmlResourceAsString("mq/trekk_kred_bestilling_flere_enheter_med_feil_summering.xml"))

            assertThat(bestilling.valideringsFeil())
                .hasSize(1)
                .contains("I arkivref/enhet 235750560/8020 stemmer ikke delsum (450.00) med summen av dens trekklinjer (451.00)")
        }
    })
