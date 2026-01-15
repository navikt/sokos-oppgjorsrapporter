package no.nav.sokos.oppgjorsrapporter.utils

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import no.nav.sokos.oppgjorsrapporter.mq.Data
import no.nav.sokos.oppgjorsrapporter.mq.Header
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.Bankkonto
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

object TestData {
    val rapportMock =
        Rapport(
            id = Rapport.Id(123),
            bestillingId = RapportBestilling.Id(114),
            orgnr = OrgNr("810007982"),
            type = RapportType.`ref-arbg`,
            datoValutert = LocalDate.parse("2024-11-01"),
            bankkonto = Bankkonto("12345678901"),
            navn = "Test Bedrift AS",
            antallRader = 3,
            antallUnderenheter = 1,
            antallPersoner = 2,
            opprettet = Instant.parse("2024-11-01T12:15:02Z"),
            arkivert = null,
        )

    val minimalPdf =
        """%PDF-2.0
1 0 obj
<<
  /Pages 2 0 R
  /Type /Catalog
>>
endobj
2 0 obj
<<
  /Count 1
  /Kids [
    3 0 R
  ]
  /Type /Pages
>>
endobj
3 0 obj
<<
  /Contents 4 0 R
  /MediaBox [ 0 0 612 792 ]
  /Parent 2 0 R
  /Resources <<
    /Font << /F1 5 0 R >>
  >>
  /Type /Page
>>
endobj
4 0 obj
<<
  /Length 44
>>
stream
BT
  /F1 24 Tf
  72 720 Td
  (Potato) Tj
ET
endstream
endobj
5 0 obj
<<
  /BaseFont /Helvetica
  /Encoding /WinAnsiEncoding
  /Subtype /Type1
  /Type /Font
>>
endobj

xref
0 6
0000000000 65535 f 
0000000009 00000 n 
0000000062 00000 n 
0000000133 00000 n 
0000000277 00000 n 
0000000372 00000 n 
trailer <<
  /Root 1 0 R
  /Size 6
  /ID [<42841c13bbf709d79a200fa1691836f8><b1d8b5838eeafe16125317aa78e666aa>]
>>
startxref
478
%%EOF"""

    fun createDataRec(
        navenhet: Int = 8020,
        bedriftsnummer: String = "933001542",
        kode: String = "H",
        tekst: String = "Sykepenger",
        fnr: String = "29070049716",
        navn: String = "wopoj hyfom",
        fraDato: LocalDate = LocalDate.parse("2025-05-01"),
        tilDato: LocalDate = LocalDate.parse("2025-05-31"),
        belop: BigDecimal = BigDecimal.valueOf(9905.00),
        maxDato: LocalDate? = LocalDate.parse("2026-07-31"),
    ): Data {

        return Data(
            navenhet = navenhet,
            bedriftsnummer = bedriftsnummer,
            kode = kode,
            tekst = tekst,
            fnr = fnr,
            navn = navn,
            fraDato = fraDato,
            tilDato = tilDato,
            belop = belop,
            maxDato = maxDato,
        )
    }

    fun createRefusjonsRapportBestilling(
        headerOrgnr: String = "974600019",
        headerBankkonto: String = "02470303400",
        headerSumBelop: BigDecimal = BigDecimal.valueOf(9093.00),
        headerValutert: LocalDate = LocalDate.parse("2025-01-28"),
        headerLinjer: Long = 1,
        datarec: List<Data> = listOf(createDataRec()),
    ): RefusjonsRapportBestilling {
        return RefusjonsRapportBestilling(
            header =
                Header(
                    orgnr = headerOrgnr,
                    bankkonto = headerBankkonto,
                    sumBelop = headerSumBelop,
                    valutert = headerValutert,
                    linjer = headerLinjer,
                ),
            datarec = datarec,
        )
    }
}

data class Ansatt(val fnr: String, val navn: String)

val riktigFormatertRefusjonArbeidsgiverPdfPayloadSortertEtterUnderenhet =
    """
                {
                  "rapportSendt": "31.10.2025",
                  "utbetalingsDato": "28.10.2025",
                  "totalsum": { "verdi": 16238.68, "formattert": "16 238,68" },
                  "bedrift": {
                    "orgnr": { "verdi": "974600019", "formattert": "974 600 019" },
                    "navn": "Helsfyr stål og plasikk",
                    "kontonummer": { "verdi": "02470303400", "formattert": "0247 03 03400" },
                    "adresse": "Veien 24, 1234, VårBy"
                  },
                  "underenheter": [
                    {
                      "totalbelop": { "verdi": 5738.68, "formattert": "5 738,68" },
                      "orgnr": { "verdi": "009876111", "formattert": "009 876 111" },
                      "utbetalinger": [
                        {
                          "ytelse": "Foreldrepenger",
                          "fnr": { "verdi": "12345678111", "formattert": "123456 78111" },
                          "navn": "Anders Andersen",
                          "periodeFra": "01.01.2025",
                          "periodeTil": "31.01.2025",
                          "maksDato": "31.07.2026",
                          "belop": "{ "verdi": 4234.00, "formattert": "4 234,00" }
                        },
                        {
                          "ytelse": "Sykepenger",
                          "fnr": { "verdi": "12345678222", "formattert": "123456 78222" },
                          "navn": "Birte Birtesen",
                          "periodeFra": "01.03.2025",
                          "periodeTil": "31.03.2025",
                          "maksDato": "31.07.2026",
                          "belop": { "verdi": 1504.68, "formattert": "1 504,68" }
                        }
                      ]
                    },
                    {
                      "totalbelop": { "verdi": 10500.00, "formattert": "10 500,00" },
                      "orgnr": { "verdi": "009876222", "formattert": "009 876 222" },
                      "utbetalinger": [
                        {
                          "ytelse": "Foreldrepenger",
                          "fnr": { "verdi": "12345678111", "formattert": "123456 78111" },
                          "navn": "Anders Andersen",
                          "periodeFra": "01.01.2025",
                          "periodeTil": "31.01.2025",
                          "maksDato": "31.07.2026",
                          "belop": { "verdi": 6500.00, "formattert": "6 500,00" }
                        },
                        {
                          "ytelse": "Sykepenger",
                          "fnr": { "verdi": "12345678222", "formattert": "123456 78222" },
                          "navn": "Birte Birtesen",
                          "periodeFra": "01.03.2025",
                          "periodeTil": "31.03.2025",
                          "maksDato": "31.07.2026",
                          "belop": { "verdi": 4000.00, "formattert": "4 000,00" }
                        }
                      ]
                    }
                  ]
                }
                """

val eregResponse =
    """
    {
      "organisasjonsnummer": "990983666",
      "navn": {
        "sammensattnavn": "NAV FAMILIE- OG PENSJONSYTELSER OSL",
        "navnelinje1": "",
        "navnelinje2": "",
        "navnelinje3": "",
        "navnelinje4": "",
        "navnelinje5": "",
        "bruksperiode": {
          "fom": "2015-01-06T21:44:04.748",
          "tom": "2015-12-06T19:45:04"
        },
        "gyldighetsperiode": {
          "fom": "2014-07-01",
          "tom": "2015-12-31"
        }
      },
      "enhetstype": "BEDR",
      "adresse": {
        "adresselinje1": "Sannergata 2",
        "adresselinje2": "",
        "adresselinje3": "",
        "postnummer": "0557",
        "poststed": "Oslo",
        "landkode": "JPN",
        "kommunenummer": "0301",
        "bruksperiode": {
          "fom": "2015-01-06T21:44:04.748",
          "tom": "2015-12-06T19:45:04"
        },
        "gyldighetsperiode": {
          "fom": "2014-07-01",
          "tom": "2015-12-31"
        },
        "type": "string"
      },
      "opphoersdato": "2016-12-31"
    }
    """
        .trimIndent()
