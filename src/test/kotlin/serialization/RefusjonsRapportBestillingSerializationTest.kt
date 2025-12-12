package serialization

import java.math.BigDecimal
import java.time.LocalDate
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.mq.Data
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.tilCSV
import no.nav.sokos.oppgjorsrapporter.rapport.generator.RefusjonsRapportPdfPayload
import no.nav.sokos.oppgjorsrapporter.utils.Ansatt
import no.nav.sokos.oppgjorsrapporter.utils.TestData.createDataRec
import no.nav.sokos.oppgjorsrapporter.utils.TestData.createRefusjonsRapportBestilling
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RefusjonsRapportBestillingSerializationTest {
    private val json = RefusjonsRapportBestilling.json

    @Test
    fun `deserialize nullable LocalDate values properly`() {
        val input =
            """
            {
                "navenhet": 1,
                "bedriftsnummer": "123456789",
                "kode": "A",
                "tekst": "Test",
                "fnr": "12345678901",
                "navn": "Test Person",
                "belop": 100.0,
                "fraDato": "2024-01-01",
                "tilDato": "2024-12-31"
            }
            """
                .trimIndent()

        val data = json.decodeFromString<Data>(input)
        assertNull(data.maxDato)
        assertThat(data.fraDato).isEqualTo("2024-01-01")
        assertThat(data.tilDato).isEqualTo("2024-12-31")
    }

    @Test
    fun `serialize null maxdato as null`() {
        val data =
            Data(
                navenhet = 1,
                bedriftsnummer = "123456789",
                kode = "A",
                tekst = "Test",
                fnr = "12345678901",
                navn = "Test Person",
                belop = BigDecimal("100.0"),
                fraDato = LocalDate.parse("2024-01-01"),
                tilDato = LocalDate.parse("2024-12-31"),
                maxDato = null,
            )
        val output = json.encodeToString(data)
        // Should not contain a non-empty string for maxdato
        assertThatJson(output)
            .isEqualTo(
                """
                {
                    "navenhet": 1,
                    "bedriftsnummer": "123456789",
                    "kode": "A",
                    "tekst": "Test",
                    "fnr": "12345678901",
                    "navn": "Test Person",
                    "belop": 100.0,
                    "fraDato": "2024-01-01",
                    "tilDato": "2024-12-31"
                }
                """
                    .trimIndent()
            )
    }

    @Test
    fun `tilCsv skal generere CSV innhold med riktig format`() {

        // maxDato (8 siffer) - Maks dato på format ÅÅÅÅMMDD. Settes til "00000000" hvis feltet mangler
        val dataRecordUtenMaxDato = createDataRec(maxDato = null)
        val dataRecordMedMaxDato = createDataRec(maxDato = LocalDate.parse("2026-07-31"))

        // navn (25 tegn) - Navn på person, padding med mellomrom til høyre hvis kortere. Semikolon, quote og linjeskift erstattes med
        // mellomrom dersom det ikke forekommer i starten eller slutten.
        val dataRecordMedNavnKortereEnn25Tegn = createDataRec(navn = "Kort navn")
        val dataRecordMedNavnLengereEnn25Tegn = createDataRec(navn = "Dette navnet er definitivt lengre enn tjuefem tegn")
        val dataRecordMedNavnMedSemikolon = createDataRec(navn = "Navn;med;semikolon")
        val dataRecordMedNavnMedEksakt25Tegn = createDataRec(navn = "Navn med nøyaktig tjuefem") // 25 tegn
        val dataRecordMedNavnMedLinjeskift = createDataRec(navn = "Navn\r\n\r\nmed\nlinjeskift")

        // belop (11 tegn) - Beløp i ører (10 siffer + fortegn). Kreditbeløp (negative) får '-' på slutten, debetbeløp får ' ' (mellomrom)
        // på slutten
        val dataRecordMedKreditBeløp = createDataRec(belop = BigDecimal("-9905.00"))
        val dataRecordMedDebitBeløp = createDataRec(belop = BigDecimal("9905.00"))
        val dataRecordMedMerSeksDesimaler = createDataRec(belop = BigDecimal("9905.126789"))

        val refusjonsRapportBestilling =
            createRefusjonsRapportBestilling(
                datarec =
                    listOf(
                        dataRecordUtenMaxDato,
                        dataRecordMedMaxDato,
                        dataRecordMedNavnKortereEnn25Tegn,
                        dataRecordMedNavnLengereEnn25Tegn,
                        dataRecordMedNavnMedSemikolon,
                        dataRecordMedNavnMedEksakt25Tegn,
                        dataRecordMedNavnMedLinjeskift,
                        dataRecordMedKreditBeløp,
                        dataRecordMedDebitBeløp,
                        dataRecordMedMerSeksDesimaler,
                    )
            )
        val forventetCsvInnhold =
            listOf(
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;wopoj hyfom              ;20250531;0000990500 ;0000000000 ;00000000",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;wopoj hyfom              ;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;Kort navn                ;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;Dette navnet er definitiv;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;Navn med semikolon       ;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;Navn med nøyaktig tjuefem;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;Navn med linjeskift      ;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;wopoj hyfom              ;20250531;0000990500-;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;wopoj hyfom              ;20250531;0000990500 ;0000000000 ;20260731",
                    "8020;974600019;933001542;H;29070049716;20250501;02470303400;wopoj hyfom              ;20250531;0000990513 ;0000000000 ;20260731",
                )
                .joinToString("\r\n") + "\r\n"

        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }

    @Test
    fun `RefusjonsRapportPdfPayload serialiseres riktig, sortert etter ytelse, underenhet, fnr, og periodeFra`() {
        val underenhet1 = "009876111"
        val underenhet2 = "009876222"

        val person1 = Ansatt(fnr = "12345678111", navn = "Anders Andersen")
        val person2 = Ansatt(fnr = "12345678222", navn = "Birte Birtesen")
        val ytelse1 = "Foreldrepenger"
        val ytelse2 = "Sykepenger"

        val ytelse1Underenhet1Person1 =
            createDataRec(
                bedriftsnummer = underenhet1,
                fnr = person1.fnr,
                navn = person1.navn,
                tekst = ytelse1,
                belop = BigDecimal("4234.00"),
                fraDato = LocalDate.parse("2025-01-01"),
                tilDato = LocalDate.parse("2025-01-31"),
            )
        val ytelse2Underenhet1Person1 =
            createDataRec(
                bedriftsnummer = underenhet1,
                fnr = person1.fnr,
                navn = person1.navn,
                tekst = ytelse2,
                belop = BigDecimal("6500.00"),
                fraDato = LocalDate.parse("2025-01-01"),
                tilDato = LocalDate.parse("2025-01-31"),
                maxDato = null,
            )

        val ytelse1Underenhet2Person2 =
            createDataRec(
                bedriftsnummer = underenhet2,
                fnr = person2.fnr,
                navn = person2.navn,
                tekst = ytelse1,
                belop = BigDecimal("1504.677"),
                fraDato = LocalDate.parse("2025-01-15"),
                tilDato = LocalDate.parse("2025-01-31"),
            )
        val ytelse2Underenhet2Person2 =
            createDataRec(
                bedriftsnummer = underenhet2,
                fnr = person2.fnr,
                navn = person2.navn,
                tekst = ytelse2,
                belop = BigDecimal("4000.00"),
                fraDato = LocalDate.parse("2025-01-15"),
                tilDato = LocalDate.parse("2025-01-31"),
            )

        val posteringer =
            listOf(
                // Har her med vilje stokket posteringer for forskjellige ytelser om hverandre, slik at vi ser at "sorter på ytelse" virker.
                ytelse2Underenhet2Person2,
                ytelse1Underenhet1Person1,
                ytelse2Underenhet1Person1,
                ytelse1Underenhet2Person2,
            )

        val totalsum = posteringer.sumOf { it.belop }

        val refusjonsRapportBestilling = createRefusjonsRapportBestilling(headerSumBelop = totalsum, datarec = posteringer)
        val now = LocalDate.parse("2025-01-31")
        val organisasjonsNavnOgAdresse =
            OrganisasjonsNavnOgAdresse(
                organisasjonsnummer = "0087654321",
                navn = "Helsfyr stål og plasikk",
                adresse = "Veien 24, 1234, VårBy",
            )
        val pdfPayload = RefusjonsRapportPdfPayload(refusjonsRapportBestilling, organisasjonsNavnOgAdresse, now)
        val actualJson = json.encodeToString(RefusjonsRapportPdfPayload.serializer(), pdfPayload)

        assertThatJson(actualJson)
            .isEqualTo(
                """
                {
                  "rapportSendt": "31.01.2025",
                  "utbetalingsDato": "28.01.2025",
                  "totalsum": {
                    "verdi": 16238.68,
                    "formattert": "16 238,68"
                  },
                  "bedrift": {
                    "orgnr": {
                      "verdi": "974600019",
                      "formattert": "974 600 019"
                    },
                    "navn": "Helsfyr stål og plasikk",
                    "kontonummer": {
                      "verdi": "02470303400",
                      "formattert": "0247 03 03400"
                    },
                    "adresse": "Veien 24, 1234, VårBy"
                  },
                  "ytelser": [
                    {
                      "ytelse": "Foreldrepenger",
                      "totalbelop": {
                        "verdi": 5738.68,
                        "formattert": "5 738,68"
                      },
                      "posteringer": [
                        {
                          "orgnr": {
                            "verdi": "009876111",
                            "formattert": "009 876 111"
                          },
                          "fnr": {
                            "verdi": "12345678111",
                            "formattert": "123456 78111"
                          },
                          "navn": "Anders Andersen",
                          "periodeFra": "01.01.2025",
                          "periodeTil": "31.01.2025",
                          "maksDato": "31.07.2026",
                          "belop": {
                            "verdi": 4234.00,
                            "formattert": "4 234,00"
                          }
                        },
                        {
                          "orgnr": {
                            "verdi": "009876222",
                            "formattert": "009 876 222"
                          },
                          "fnr": {
                            "verdi": "12345678222",
                            "formattert": "123456 78222"
                          },
                          "navn": "Birte Birtesen",
                          "periodeFra": "15.01.2025",
                          "periodeTil": "31.01.2025",
                          "maksDato": "31.07.2026",
                          "belop": {
                            "verdi": 1504.68,
                            "formattert": "1 504,68"
                          }
                        }
                      ]
                    },
                    {
                      "ytelse": "Sykepenger",
                      "totalbelop": {
                        "verdi": 10500.00,
                        "formattert": "10 500,00"
                      },
                      "posteringer": [
                        {
                          "orgnr": {
                            "verdi": "009876111",
                            "formattert": "009 876 111"
                          },
                          "fnr": {
                            "verdi": "12345678111",
                            "formattert": "123456 78111"
                          },
                          "navn": "Anders Andersen",
                          "periodeFra": "01.01.2025",
                          "periodeTil": "31.01.2025",
                          "belop": {
                            "verdi": 6500.00,
                            "formattert": "6 500,00"
                          }
                        },
                        {
                          "orgnr": {
                            "verdi": "009876222",
                            "formattert": "009 876 222"
                          },
                          "fnr": {
                            "verdi": "12345678222",
                            "formattert": "123456 78222"
                          },
                          "navn": "Birte Birtesen",
                          "periodeFra": "15.01.2025",
                          "periodeTil": "31.01.2025",
                          "maksDato": "31.07.2026",
                          "belop": {
                            "verdi": 4000.00,
                            "formattert": "4 000,00"
                          }
                        }
                      ]
                    }
                  ]
                }
                """
                    .trimIndent()
            )
    }
}
