package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.kotest.core.spec.style.FunSpec
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.sokos.oppgjorsrapporter.mq.TrekkHendBestilling

class TrekkHendPdfMapperTest :
    FunSpec({
        test("Generer trekk-hend/kreditor payload til pdfgenrs og valider felter") {
            val bestilling =
                TrekkHendBestilling.decode(javaClass.classLoader.getResourceAsStream("mq/trekk_hend_bestilling-kreditor.xml")!!)

            val payload = bestilling.mapTilTrekkHendPdfPayload()

            val jsonPayload = PdfgenrsHttpClientSetup.jsonConfig.encodeToString(payload)

            assertThatJson(jsonPayload)
                .isEqualTo(
                    """
                    {
                      "dato": "21.04.2026",
                      "mottaker": {
                        "type": "kreditor",
                        "orgnr": {
                          "formattert": "859 503 241",
                          "verdi": "859503241"
                        },
                        "navn": "McDuck inkasso AS",
                        "adresse": "Postboks 313, 3158 ANDEBY"
                      },
                      "trekkHendelser": [
                        {
                          "fnr": {
                            "formattert": "184538 66986",
                            "verdi": "18453866986"
                          },
                          "navn": "Donald Duck",
                          "namsmannOrgnr": {
                            "formattert": "087 453 421",
                            "verdi": "087453421"
                          },
                          "kid": "0107036257420540624",
                          "hendelse": "Ingen ytelse"
                        },
                        {
                          "fnr": {
                            "formattert": "224685 28375",
                            "verdi": "22468528375"
                          },
                          "navn": "Gulbrand Gråstein",
                          "namsmannOrgnr": {
                            "formattert": "141 619 942",
                            "verdi": "141619942"
                          },
                          "kid": "0103064541812328464",
                          "hendelse": "Opphør ytelse"
                        }
                      ]
                    }
                    """
                        .trimIndent()
                )
        }

        test("Generer trekk-hend/namsmann payload til pdfgenrs og valider felter") {
            val bestilling =
                TrekkHendBestilling.decode(javaClass.classLoader.getResourceAsStream("mq/trekk_hend_bestilling-namsmann.xml")!!)

            val payload = bestilling.mapTilTrekkHendPdfPayload()

            val jsonPayload = PdfgenrsHttpClientSetup.jsonConfig.encodeToString(payload)

            assertThatJson(jsonPayload)
                .isEqualTo(
                    """
                    {
                      "dato": "21.04.2026",
                      "mottaker": {
                        "type": "namsmann",
                        "orgnr": {
                          "formattert": "087 453 421",
                          "verdi": "087453421"
                        },
                        "navn": "NAMSFOGDEN I ANDEBY",
                        "adresse": "Postboks 313, 3158"
                      },
                      "trekkHendelser": [
                        {
                          "fnr": {
                            "formattert": "184538 66986",
                            "verdi": "18453866986"
                          },
                          "navn": "Donald Duck",
                          "namsmannOrgnr": null,
                          "kid": "0107036257420540624",
                          "hendelse": "Ingen ytelse"
                        },
                        {
                          "fnr": {
                            "formattert": "245089 24131",
                            "verdi": "24508924131"
                          },
                          "navn": "Guffen Gås",
                          "namsmannOrgnr": null,
                          "kid": "5900562964792591",
                          "hendelse": "Ingen ytelse"
                        },
                        {
                          "fnr": {
                            "formattert": "284820 19570",
                            "verdi": "28482019570"
                          },
                          "navn": "Petter Smart",
                          "namsmannOrgnr": null,
                          "kid": "01626785861606857982",
                          "hendelse": "Opphør ytelse"
                        }
                      ]
                    }
                    """
                        .trimIndent()
                )
        }
    })
