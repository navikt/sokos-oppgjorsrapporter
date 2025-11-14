package serialization

import java.math.BigDecimal
import java.time.LocalDate
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import no.nav.sokos.oppgjorsrapporter.mq.Data
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
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
                    "bedriftsnummer": 123456789,
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
                bedriftsnummer = 123456789,
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
                    "bedriftsnummer": 123456789,
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
    fun `tilCsv skal generere CSV innhold med tom maxDato når maxDato feltet mangler i meldingen fra UR`() {
        val refusjonMeldingUtenMaxdato =
            """
            {
              "header": {
                "orgnr": 974600019,
                "bankkonto": "02470303400",
                "sumBelop": 9093.00,
                "valutert": "2025-10-28",
                "linjer": 1
              },
              "datarec": [
                {
                  "navenhet": 8020,
                  "bedriftsnummer": 933001542,
                  "kode": "H",
                  "tekst": "Sykepenger feriepenger",
                  "fnr": "29070049716",
                  "navn": "wopoj hyfom",
                  "fraDato": "2025-05-01",
                  "tilDato": "2025-05-31",
                  "belop": 9905.00
                }
              ]
            }
                    """
                .trimIndent()

        val refusjonsRapportBestilling = json.decodeFromString<RefusjonsRapportBestilling>(refusjonMeldingUtenMaxdato)

        val forventetCsvInnhold = "8020;974600019;933001542;H;02470303400;20250501;29070049716;wopoj hyfom;20250531;990500;0000000000;"
        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }

    @Test
    fun `tilCsv skal generere CSV innhold med riktig formatert maxDato når feltet er tilstede i meldingen fra UR`() {
        val refusjonMeldingMedMaxdato =
            """
            {
              "header": {
                "orgnr": 974600019,
                "bankkonto": "02470303400",
                "sumBelop": 9093.00,
                "valutert": "2025-10-28",
                "linjer": 1
              },
              "datarec": [
                {
                  "navenhet": 8020,
                  "bedriftsnummer": 933001542,
                  "kode": "H",
                  "tekst": "Sykepenger feriepenger",
                  "fnr": "29070049716",
                  "navn": "wopoj hyfom",
                  "fraDato": "2025-05-01",
                  "tilDato": "2025-05-31",
                  "belop": 9905.00,
                  "maxDato": "2026-07-31"
                }
              ]
            }
                    """
                .trimIndent()

        val refusjonsRapportBestilling = json.decodeFromString<RefusjonsRapportBestilling>(refusjonMeldingMedMaxdato)

        val forventetCsvInnhold =
            "8020;974600019;933001542;H;02470303400;20250501;29070049716;wopoj hyfom;20250531;990500;0000000000;20260731"
        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }

    @Test
    fun `tilCsv skal generere CSV innhold med minus fortegn bak beløpet i ører når beløpet er kredit`() {
        val refusjonMeldingMedKreditBeløp =
            """
            {
              "header": {
                "orgnr": 974600019,
                "bankkonto": "02470303400",
                "sumBelop": 9093.00,
                "valutert": "2025-10-28",
                "linjer": 1
              },
              "datarec": [
                {
                  "navenhet": 8020,
                  "bedriftsnummer": 933001542,
                  "kode": "H",
                  "tekst": "Sykepenger feriepenger",
                  "fnr": "29070049716",
                  "navn": "wopoj hyfom",
                  "fraDato": "2025-05-01",
                  "tilDato": "2025-05-31",
                  "belop": 9905.00,
                  "maxDato": "2026-07-31"
                }
              ]
            }
                    """
                .trimIndent()

        val refusjonsRapportBestilling = json.decodeFromString<RefusjonsRapportBestilling>(refusjonMeldingMedKreditBeløp)

        val forventetCsvInnhold =
            "8020;974600019;933001542;H;02470303400;20250501;29070049716;wopoj hyfom;20250531;990500;0000000000;20260731"
        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }

    @Test
    fun `tilCsv skal generere CSV innhold med flere linjer når meldingen fra UR har flere linjer i datarec arrayen`() {
        val refusjonMeldingMedKreditBeløp =
            """
            {
              "header": {
                "orgnr": 974600019,
                "bankkonto": "02470303400",
                "sumBelop": 9093.00,
                "valutert": "2025-10-28",
                "linjer": 1
              },
              "datarec": [
                {
                  "navenhet": 8020,
                  "bedriftsnummer": 933001542,
                  "kode": "H",
                  "tekst": "Sykepenger feriepenger",
                  "fnr": "29070049716",
                  "navn": "wopoj hyfom",
                  "fraDato": "2025-05-01",
                  "tilDato": "2025-05-31",
                  "belop": 9905.00
                },
                {
                  "navenhet": 8020,
                  "bedriftsnummer": 933001542,
                  "kode": "H",
                  "tekst": "Sykepenger feriepenger",
                  "fnr": "29070049716",
                  "navn": "wopoj hyfom",
                  "fraDato": "2025-05-01",
                  "tilDato": "2025-05-31",
                  "belop": 9905.00,
                  "maxDato": "2026-07-31"
                },
                {
                  "navenhet": 8020,
                  "bedriftsnummer": 933001542,
                  "kode": "H",
                  "tekst": "Sykepenger feriepenger",
                  "fnr": "29070049716",
                  "navn": "wopoj hyfom",
                  "fraDato": "2025-05-01",
                  "tilDato": "2025-05-31",
                  "belop": 9905.00,
                  "maxDato": "2026-07-31"
                }
                
              ]
            }
                    """
                .trimIndent()

        val refusjonsRapportBestilling = json.decodeFromString<RefusjonsRapportBestilling>(refusjonMeldingMedKreditBeløp)

        val forventetCsvInnhold =
            """
                8020;974600019;933001542;H;02470303400;20250501;29070049716;wopoj hyfom;20250531;990500;0000000000;
                8020;974600019;933001542;H;02470303400;20250501;29070049716;wopoj hyfom;20250531;990500;0000000000;20260731
                8020;974600019;933001542;H;02470303400;20250501;29070049716;wopoj hyfom;20250531;990500;0000000000;20260731
            """
                .trimIndent()
        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }
}
