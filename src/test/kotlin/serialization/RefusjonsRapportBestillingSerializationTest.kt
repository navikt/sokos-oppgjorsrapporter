package serialization

import java.math.BigDecimal
import java.time.LocalDate
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import no.nav.sokos.oppgjorsrapporter.mq.Data
import no.nav.sokos.oppgjorsrapporter.mq.LINJESKIFT
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
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
        val dataRecordMedNavnMedLinjeskift = createDataRec(navn = "Navn${LINJESKIFT}${LINJESKIFT}med\nlinjeskift")

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
                .joinToString(LINJESKIFT) + LINJESKIFT

        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }

    @Test
    fun `tilCsv skal formatere orgnr og bedriftsnummer med ledende 0-er dersom de er kortere enn 9 siffer`() {
        val dataRecordMedOrgnrMedLedende0 = createDataRec(bedriftsnummer = 12345678)
        val refusjonsRapportBestilling =
            createRefusjonsRapportBestilling(headerOrgnr = 12345678, datarec = listOf(dataRecordMedOrgnrMedLedende0))

        val forventetCsvInnhold =
            "8020;012345678;012345678;H;29070049716;20250501;02470303400;wopoj hyfom              ;20250531;0000990500 ;0000000000 ;20260731${LINJESKIFT}"

        val faktiskCsvInnhold = refusjonsRapportBestilling.tilCSV()

        assertThat(faktiskCsvInnhold).isEqualTo(forventetCsvInnhold)
    }
}
