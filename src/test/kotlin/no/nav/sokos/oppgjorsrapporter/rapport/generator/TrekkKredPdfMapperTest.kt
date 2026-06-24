package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.FunSpec
import java.time.LocalDate
import kotlinx.serialization.json.Json
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredPdfMapper.mapTilTrekkKredRapportPdfPayload
import no.nav.sokos.oppgjorsrapporter.utils.xmlResourceAsString
import tools.jackson.module.kotlin.readValue

class TrekkKredPdfMapperTest :
    FunSpec({
        val fixedDate = LocalDate.of(2026, 3, 10)
        val onoa = OrganisasjonsNavnOgAdresse(organisasjonsnummer = "123456789", navn = "ONOG ONOG OH NO ONONOGO", adresse = "Onogata 33")

        test("Generer trekk-kred payload til pdfgen og valider felter") {
            val bestilling = TrekkKredRapportBestilling.decode(xmlResourceAsString("mq/trekk_kred_bestilling_flere_enheter.xml"))

            val payload = bestilling.mapTilTrekkKredRapportPdfPayload(onoa, fixedDate)

            val jsonPayload = Json.encodeToString(payload)

            assertThatJson(jsonPayload)
                .isEqualTo(
                    """
                    {
                      "rapportSendt": "10.03.2026",
                      "utbetalingsDato": "17.02.2026",
                      "totalsum": "850,00",
                      "periode": {
                        "fra": "01.02.2026",
                        "til": "28.02.2026"
                      },
                      "bedrift": {
                        "orgnr": "123456789",
                        "tssid": "80000815306",
                        "navn": "ONOG ONOG OH NO ONONOGO",
                        "kontonummer": "42141415926",
                        "adresse": "Onogata 33"
                      },
                      "enheter": [
                        {
                          "navn": "Nav økonomi stønad",
                          "sumEnhet": "325,00",
                          "orgnr": "921221967",
                          "arkivreferanser": [
                            {
                              "arkivref": "235753327",
                              "refnrsum": "275,00",
                              "trekk": [
                                {
                                  "fnr": "15267807496",
                                  "navn": "Dolly DUCK MED VENNER",
                                  "saksreferanse": "Molde barnevern",
                                  "periodeFra": "01.02.2026",
                                  "periodeTil": "28.02.2026",
                                  "belop": "100,00",
                                  "tssId": "80000815306"
                                },
                                {
                                  "fnr": "15267807496",
                                  "navn": "DeDaDe DaDeDa",
                                  "saksreferanse": "Molde kommune",
                                  "periodeFra": "01.02.2026",
                                  "periodeTil": "28.02.2026",
                                  "belop": "175,00",
                                  "tssId": "80000815306"
                                }
                              ]
                            },
                            {
                              "arkivref": "235750560",
                              "refnrsum": "50,00",
                              "trekk": [
                                {
                                  "fnr": "19486545678",
                                  "navn": "JJJJJCCCCCBBB AAAAAAAAAAA",
                                  "saksreferanse": "Barnevernstjenesten",
                                  "periodeFra": "01.02.2026",
                                  "periodeTil": "28.02.2026",
                                  "belop": "−200,00",
                                  "tssId": "80000818309"
                                },
                                {
                                  "fnr": "29667789766",
                                  "navn": "BURGER MED POMMES FRITES",
                                  "saksreferanse": "Frivillig forvaltning - H. Berg",
                                  "periodeFra": "01.02.2026",
                                  "periodeTil": "28.02.2026",
                                  "belop": "250,00",
                                  "tssId": "80000818309"
                                }
                              ]
                            }
                          ]
                        },
                        {
                          "navn": "Nav økonomi pensjon",
                          "sumEnhet": "525,00",
                          "orgnr": "921221967",
                          "arkivreferanser": [
                            {
                              "arkivref": "235753327",
                              "refnrsum": "150,00",
                              "trekk": [
                                {
                                  "fnr": "15267807496",
                                  "navn": "DONALD DUCK MED VENNER",
                                  "saksreferanse": "Molde barnevern",
                                  "periodeFra": "01.02.2026",
                                  "periodeTil": "28.02.2026",
                                  "belop": "150,00",
                                  "tssId": "80000815306"
                                }
                              ]
                            },
                            {
                              "arkivref": "235750560",
                              "refnrsum": "375,00",
                              "trekk": [
                                {
                                  "fnr": "15267807496",
                                  "navn": "Mr DUCK",
                                  "saksreferanse": "Molde barnevern",
                                  "periodeFra": "01.02.2026",
                                  "periodeTil": "28.02.2026",
                                  "belop": "375,00",
                                  "tssId": "80000815306"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """
                        .trimIndent()
                )
        }

        test("Når validering etter mapping feiler så skal det kastes exception") {
            val bestilling =
                TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(
                    xmlResourceAsString("mq/trekk_kred_bestilling_flere_enheter_med_feil_summering.xml")
                )

            shouldThrowWithMessage<Exception>("Validering av pdf payload for bestilling feilet") {
                bestilling.mapTilTrekkKredRapportPdfPayload(onoa, fixedDate)
            }
        }
    })
