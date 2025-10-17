package no.nav.sokos.oppgjorsrapporter.utils

import java.time.Instant
import java.time.LocalDate
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

object TestData {
    val rapportMock =
        Rapport(
            id = Rapport.Id(123),
            orgNr = OrgNr("810007982"),
            type = RapportType.K27,
            tittel = "K27 for Snikende Maur 2024-11-01",
            datoValutert = LocalDate.parse("2024-11-01"),
            opprettet = Instant.parse("2024-11-01T12:15:02Z"),
            arkivert = null,
        )
}
