@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotliquery.Row
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer

@Serializable @JvmInline value class OrgNr(val raw: String)

enum class RapportType(val altinnRessurs: String) {
    K27("nav_utbetaling_oppgjorsrapport-refusjon-arbeidsgiver"), // Ny kode: "refusjon"?
    T12("Ikke definert ennå"), // Ny kode: "trekkhendelser"?
    T14("Ikke definert ennå"), // Ny kode: "trekk"?
}

sealed interface RapportBestillingFelter {
    val mottatt: Instant
    val mottattFra: String
    val dokument: String
    val genererSom: RapportType
}

data class UlagretRapportBestilling(
    override val mottatt: Instant,
    override val mottattFra: String,
    override val dokument: String,
    override val genererSom: RapportType,
) : RapportBestillingFelter

data class RapportBestilling(
    val id: Id,
    override val mottatt: Instant,
    override val mottattFra: String,
    override val dokument: String,
    override val genererSom: RapportType,
    val ferdigProsessert: Instant? = null,
) : RapportBestillingFelter {
    @Serializable @JvmInline value class Id(val raw: Long)

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        mottatt = row.instant("mottatt"),
        mottattFra = row.string("mottatt_fra"),
        dokument = row.string("dokument"),
        genererSom = RapportType.valueOf(row.string("generer_som")),
        ferdigProsessert = row.instantOrNull("ferdig_prosessert"),
    )
}

sealed interface RapportFelter {
    val bestillingId: RapportBestilling.Id
    val orgNr: OrgNr
    val type: RapportType
    val tittel: String
    val datoValutert: LocalDate
}

data class UlagretRapport(
    override val bestillingId: RapportBestilling.Id,
    override val orgNr: OrgNr,
    override val type: RapportType,
    override val tittel: String,
    override val datoValutert: LocalDate,
) : RapportFelter

@Serializable
data class Rapport(
    val id: Id,
    override val bestillingId: RapportBestilling.Id,
    override val orgNr: OrgNr,
    override val type: RapportType,
    override val tittel: String,
    override val datoValutert: LocalDate,
    val opprettet: Instant,
    val arkivert: Instant? = null,
) : RapportFelter {
    fun filnavn(format: VariantFormat): String =
        "${orgNr.raw}_${type.name}_${DateTimeFormatter.ISO_LOCAL_DATE.format(datoValutert)}.${format.extension()}"

    @Serializable @JvmInline value class Id(val raw: Long)

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        bestillingId = RapportBestilling.Id(row.long("bestilling_id")),
        orgNr = OrgNr(row.string("orgnr")),
        type = RapportType.valueOf(row.string("type")),
        tittel = row.string("tittel"),
        datoValutert = row.localDate("dato_valutert"),
        opprettet = row.instant("opprettet"),
        arkivert = row.instantOrNull("arkivert"),
    )

    @kotlinx.serialization.Transient val erArkivert: Boolean = arkivert != null
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
        RAPPORT_BESTILLING_MOTTATT,
        RAPPORT_OPPRETTET,
        RAPPORT_ARKIVERT,
        VARIANT_OPPRETTET,
        VARIANT_NEDLASTET,
    }
}
