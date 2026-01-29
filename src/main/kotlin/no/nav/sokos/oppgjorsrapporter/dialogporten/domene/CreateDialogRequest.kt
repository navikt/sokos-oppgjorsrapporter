package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

data class CreateDialogRequest(
    val rapportType: RapportType,
    val orgnr: OrgNr,
    val title: String,
    val summary: String,
    val externalReference: String,
    val idempotentKey: String,
    val isApiOnly: Boolean,
    val transmissions: List<Transmission>,
    val guiActions: List<GuiAction>,
    val apiActions: List<ApiAction>,
)
