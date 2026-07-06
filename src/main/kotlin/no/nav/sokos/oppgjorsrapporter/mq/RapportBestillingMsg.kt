package no.nav.sokos.oppgjorsrapporter.mq

import no.nav.sokos.oppgjorsrapporter.rapport.UlagretRapport

sealed class RapportBestillingMsg {
    abstract fun nevntInfo(): List<UlagretRapport.NevntInfo>

    abstract fun valideringsFeil(): List<String>
}
