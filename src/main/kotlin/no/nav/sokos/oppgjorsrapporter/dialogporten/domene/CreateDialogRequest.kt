package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr

data class CreateDialogRequest(
    val orgnr: OrgNr,
    val title: String,
    val summary: String,
    val externalReference: String,
    val idempotentKey: String,
    val isApiOnly: Boolean = true,
    val transmissions: List<Transmission>,
)
