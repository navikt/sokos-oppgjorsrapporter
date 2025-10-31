@file:UseSerializers(LocalDateSerializer::class, InstantSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotliquery.Row

@Serializable @JvmInline value class OrgNr(val raw: String)

enum class RapportType(val altinnRessurs: String) {
    K27("nav_utbetaling_oppgjorsrapport-refusjon-arbeidsgiver"),
    T12("Ikke definert ennå"),
    T14("Ikke definert ennå"),
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

abstract class AsStringSerializer<T : Any>(serialName: String, private val parse: (String) -> T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        value.toString().let(encoder::encodeString)
    }

    override fun deserialize(decoder: Decoder): T = decoder.decodeString().runCatching(parse).getOrElse { throw SerializationException(it) }
}

object LocalDateSerializer :
    AsStringSerializer<LocalDate>(serialName = "utbetaling.pengeflyt.kotlinx.LocalDateSerializer", parse = LocalDate::parse)

object InstantSerializer :
    AsStringSerializer<Instant>(serialName = "utbetaling.pengeflyt.kotlinx.LocalDateSerializer", parse = Instant::parse)

@Serializable
data class Rapport(
    val id: Id,
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
