package no.nav.sokos.oppgjorsrapporter.rapport.varsel

import java.time.Instant
import kotliquery.Row
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport

data class UlagretVarsel(val rapportId: Rapport.Id, val system: VarselSystem, val opprettet: Instant)

data class Varsel(
    val id: Id,
    val rapportId: Rapport.Id,
    val system: VarselSystem,
    val opprettet: Instant,
    val antallForsok: Int,
    val nesteForsok: Instant,
) {
    @JvmInline value class Id(val raw: Long)

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        rapportId = Rapport.Id(row.long("rapport_id")),
        system = VarselSystem.valueOf(row.string("system")),
        opprettet = row.instant("opprettet"),
        antallForsok = row.int("antall_forsok"),
        nesteForsok = row.instant("neste_forsok"),
    )
}
