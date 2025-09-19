package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.io.bytestring.ByteString
import kotliquery.Row

@JvmInline value class OrgNr(val raw: String)

enum class RapportType {
    K27,
    T12,
    T14,
}

sealed interface RapportFelter {
    val orgNr: OrgNr
    val type: RapportType
    val tittel: String
    val datoValutert: LocalDate
}

data class UlagretRapport(
    override val orgNr: OrgNr,
    override val type: RapportType,
    override val tittel: String,
    override val datoValutert: LocalDate,
) : RapportFelter

data class Rapport(
    val id: Id,
    override val orgNr: OrgNr,
    override val type: RapportType,
    override val tittel: String,
    override val datoValutert: LocalDate,
    val opprettet: Instant,
    val arkivert: Instant?,
) : RapportFelter {
    fun filnavn(format: VariantFormat): String =
        "${orgNr.raw}_${type.name}_${DateTimeFormatter.ISO_LOCAL_DATE.format(datoValutert)}.${format.extension()}"

    @JvmInline value class Id(val raw: Long)

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        orgNr = OrgNr(row.string("orgnr")),
        type = RapportType.valueOf(row.string("type")),
        tittel = row.string("tittel"),
        datoValutert = row.localDate("dato_valutert"),
        opprettet = row.instant("opprettet"),
        arkivert = row.instantOrNull("arkivert"),
    )
}

enum class VariantFormat(val contentType: String) {
    Pdf("application/pdf"),
    Csv("text/csv");

    companion object {
        fun withContentType(contentType: String): VariantFormat =
            VariantFormat.entries.find { it.contentType == contentType } ?: throw IllegalArgumentException("$contentType is not supported.")
    }
}

private fun VariantFormat.extension(): String =
    when (this) {
        VariantFormat.Csv -> "csv"
        VariantFormat.Pdf -> "pdf"
    }

sealed interface VariantFelter {
    val rapportId: Rapport.Id
    val format: VariantFormat
    val filnavn: String
}

data class UlagretVariant(
    override val rapportId: Rapport.Id,
    override val format: VariantFormat,
    override val filnavn: String,
    val innhold: ByteString,
) : VariantFelter

data class Variant(
    val id: Id,
    override val rapportId: Rapport.Id,
    override val format: VariantFormat,
    override val filnavn: String,
    val bytes: Long,
) : VariantFelter {
    @JvmInline value class Id(val raw: Long)

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        rapportId = Rapport.Id(row.long("rapport_id")),
        format = VariantFormat.withContentType(row.string("format")),
        filnavn = row.string("filnavn"),
        bytes = row.long("bytes"),
    )
}

data class RapportAudit(
    val id: Id,
    val rapportId: Rapport.Id,
    val variantId: Variant.Id?,
    val tidspunkt: Instant,
    val hendelse: Hendelse,
    val brukernavn: String,
    val tekst: String?,
) {
    @JvmInline value class Id(val raw: Long)

    enum class Hendelse {
        RAPPORT_OPPRETTET,
        RAPPORT_ARKIVERT,
        VARIANT_OPPRETTET,
        VARIANT_NEDLASTET,
    }
}
