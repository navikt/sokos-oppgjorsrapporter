package no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.kontrakter

import kotlinx.serialization.Serializable

@Serializable
data class PersonDetailResponseDTO(
    val title: AvvisningskodeDTO,
    val begrunnelse: String,
    val traceId: String,
    val brukerIdent: String,
    val navIdent: String,
    val kanOverstyres: Boolean,
)
