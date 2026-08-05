package no.nav.sokos.oppgjorsrapporter.mq

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.TestUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode

class RefusjonsRapportBestillingTest :
    FunSpec({
        context("RefusjonsRapportBestilling") {
            test("kan lese inn json") {
                assertThatCode {
                        val _ = RefusjonsRapportBestilling.decode(TestUtil.readFile("mq/refusjon_bestilling_gyldig.json"))
                    }
                    .doesNotThrowAnyException()
            }

            test("kan validere input") {
                val bestilling = RefusjonsRapportBestilling.decode(TestUtil.readFile("mq/refusjon_bestilling_ugyldig.json"))
                // Verifiser at testdataene fortsatt har mottaker-orgnr i "fnr"-feltet på en av posteringene
                assertThat(bestilling.datarec.map { it.fnr.raw }).contains("00" + bestilling.header.orgnr.raw)

                assertThat(bestilling.valideringsFeil())
                    .hasSize(3)
                    .anyMatch { it.startsWith("Ikke gyldig orgnr: ") }
                    // Selv om mottaker-orgnr finnes i et "fnr"-felt, skal ikke dette gi "gyldig fnr"-valideringsfeil:
                    .anyMatch { it.startsWith("Ikke gyldig fnr: ") && !it.contains(bestilling.header.orgnr.raw) }
                    .anyMatch { it.startsWith("Ikke gyldig bankkonto: ") }
            }
        }
    })
