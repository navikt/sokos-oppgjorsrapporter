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
        tekst: String = "Sykepenger feriepenger",
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
        headerValutert: LocalDate = LocalDate.parse("2025-10-28"),
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
