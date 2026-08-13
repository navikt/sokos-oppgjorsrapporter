package no.nav.sokos.oppgjorsrapporter.fager

import kotlinx.serialization.Serializable

@Serializable
data class AltinnTilgangerRequest(val filter: AltinnTilgangerFilter)

@Serializable
data class AltinnTilgangerFilter(val altinn3Tilganger: List<String>)
