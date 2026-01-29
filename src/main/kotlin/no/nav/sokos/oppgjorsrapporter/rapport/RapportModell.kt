@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotliquery.Row

@Serializable @JvmInline value class OrgNr(val raw: String)

@Serializable @JvmInline value class Bankkonto(val raw: String)

@Serializable @JvmInline value class OrgNavn(val raw: String)

enum class RapportType(val altinnRessurs: String, val configKey: String?) {
    @JsonNames("K27") `ref-arbg`("nav_utbetaling_oppgjorsrapport-refusjon-arbeidsgiver", "refusjon"),
    @JsonNames("T12") `trekk-hend`("Ikke definert ennå", null),
    @JsonNames("T14") `trekk-kred`("Ikke definert ennå", null),
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
    val orgnr: OrgNr
    val orgNavn: OrgNavn?
    val type: RapportType
    val datoValutert: LocalDate
    val bankkonto: Bankkonto?
    val antallRader: Int
    val antallUnderenheter: Int?
    val antallPersoner: Int?
}

data class UlagretRapport(
    override val bestillingId: RapportBestilling.Id,
    override val orgnr: OrgNr,
    override val orgNavn: OrgNavn?,
    override val type: RapportType,
    override val datoValutert: LocalDate,
    override val bankkonto: Bankkonto?,
    override val antallRader: Int,
    override val antallUnderenheter: Int?,
    override val antallPersoner: Int?,
) : RapportFelter

data class Rapport(
    val id: Id,
    override val bestillingId: RapportBestilling.Id,
    override val orgnr: OrgNr,
    override val orgNavn: OrgNavn?,
    override val type: RapportType,
    override val datoValutert: LocalDate,
    override val bankkonto: Bankkonto?,
    override val antallRader: Int,
    override val antallUnderenheter: Int?,
    override val antallPersoner: Int?,
    val opprettet: Instant,
    val arkivert: Instant? = null,
    val uuid: UUID,
    val dialogportenUuid: UUID? = null,
) : RapportFelter {
    fun filnavn(format: VariantFormat): String =
        "${orgnr.raw}_${type.name}_${DateTimeFormatter.ISO_LOCAL_DATE.format(datoValutert)}.${format.extension()}"

    @Serializable @JvmInline value class Id(val raw: Long)

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        bestillingId = RapportBestilling.Id(row.long("bestilling_id")),
        orgnr = OrgNr(row.string("orgnr")),
        orgNavn = row.stringOrNull("org_navn")?.let { OrgNavn(it) },
        type = RapportType.valueOf(row.string("type")),
        datoValutert = row.localDate("dato_valutert"),
        bankkonto = row.stringOrNull("bankkonto")?.let { Bankkonto(it) },
        antallRader = row.int("antall_rader"),
        antallUnderenheter = row.intOrNull("antall_underenheter"),
        antallPersoner = row.intOrNull("antall_personer"),
        opprettet = row.instant("opprettet"),
        arkivert = row.instantOrNull("arkivert"),
        uuid = UUID.fromString(row.string("uuid")),
        dialogportenUuid = row.stringOrNull("dialogporten_uuid")?.let { UUID.fromString(it) },
    )

    val erArkivert: Boolean = arkivert != null
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

    constructor(
        row: Row
    ) : this(
        id = Id(row.long("id")),
        rapportId = Rapport.Id(row.long("rapport_id")),
        variantId = row.longOrNull("variant_id")?.let { Variant.Id(it) },
        tidspunkt = row.instant("tidspunkt"),
        hendelse = Hendelse.valueOf(row.string("hendelse")),
        brukernavn = row.string("brukernavn"),
        tekst = row.stringOrNull("tekst"),
    )

    enum class Hendelse {
        RAPPORT_BESTILLING_MOTTATT,
        RAPPORT_OPPRETTET,
        RAPPORT_ARKIVERT,
        RAPPORT_DEARKIVERT,
        VARIANT_OPPRETTET,
        VARIANT_NEDLASTET,
    }
}
