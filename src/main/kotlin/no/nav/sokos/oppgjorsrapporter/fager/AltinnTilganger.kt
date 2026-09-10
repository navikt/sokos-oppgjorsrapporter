package no.nav.sokos.oppgjorsrapporter.fager

import kotlinx.serialization.Serializable

@Serializable
data class AltinnTilganger(
    val hierarki: List<AltinnTilgang>,
    val orgNrTilTilganger: Map<String, Set<String>>,
    val tilgangTilOrgNr: Map<String, Set<String>>,
    val isError: Boolean,
)
