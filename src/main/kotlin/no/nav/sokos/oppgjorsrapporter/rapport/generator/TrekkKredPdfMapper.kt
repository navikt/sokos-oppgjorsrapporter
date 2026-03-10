package no.nav.sokos.oppgjorsrapporter.rapport.generator

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.utils.Bankkonto
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr

object TrekkKredPdfMapper {
    private val logger = KotlinLogging.logger {}

    fun TrekkKredRapportBestilling.mapTilTrekkKredRapportPdfPayload(
        organisasjonsNavnOgAdresse: OrganisasjonsNavnOgAdresse,
        rapportSendt: LocalDate,
    ): TrekkKredRapportPdfPayload {
        val urData = brukerData.brevinfo.variableFelter.ur
        val payload =
            TrekkKredRapportPdfPayload(
                    rapportSendt = rapportSendt,
                    utbetalingsDato = dato,
                    totalsum = urData.sumTotal.belop,
                    periode = Periode(fra = urData.rapportFom, urData.rapportTom),
                    bedrift =
                        Bedrift(
                            orgnr = organisasjonsNavnOgAdresse.organisasjonsnummer,
                            tssid = urData.tssId,
                            navn = organisasjonsNavnOgAdresse.navn,
                            kontonummer = urData.kontonummer,
                            adresse = organisasjonsNavnOgAdresse.adresse,
                        ),
                    enheter = mapEnheter(this),
                )
                .also {
                    if (!it.validerPayload(this)) {
                        throw Exception("Validering av pdf payload for bestilling feilet")
                    }
                }

        return payload
    }

    private fun mapEnheter(bestilling: TrekkKredRapportBestilling): List<Enhet> {
        val urData = bestilling.brukerData.brevinfo.variableFelter.ur

        return urData.arkivRefList
            .flatMap { arkiveRef -> arkiveRef.enhetList }
            .distinctBy { it.enhetnr }
            .map { enhet ->
                val arkivReferanser = urData.arkivRefList.filter { arkivRef -> arkivRef.enhetList.any { it.enhetnr == enhet.enhetnr } }

                Enhet(
                    navn = enhet.navn.trim().takeUnless { it.isBlank() } ?: NavEnhet.navnForEnhet(enhet.enhetnr),
                    sumEnhet =
                        urData.arkivRefList
                            .flatMap { arkiveRef -> arkiveRef.enhetList }
                            .filter { it.enhetnr == enhet.enhetnr }
                            .sumOf { it.delsum.belop },
                    orgnr = urData.orgnummer,
                    arkivreferanser =
                        arkivReferanser.map { arkivRef ->
                            val treffListe = arkivRef.enhetList.filter { it.enhetnr == enhet.enhetnr }.flatMap { it.trekkLinjeList }

                            Arkivreferanse(
                                arkivref = arkivRef.nr,
                                refnrsum = treffListe.sumOf { it.belop }, // arkivRef.delsumRef.belop,
                                trekk =
                                    treffListe.map {
                                        Trekk(
                                            fnr = it.fnr,
                                            navn = it.navn,
                                            saksreferanse = it.saksreferanse,
                                            periodeFra = it.trekkFOM,
                                            periodeTil = it.trekkTOM,
                                            belop = it.belop.toString(),
                                            tssId = it.dettssid,
                                        )
                                    },
                            )
                        },
                )
            }
    }

    fun TrekkKredRapportPdfPayload.validerPayload(bestilling: TrekkKredRapportBestilling): Boolean {
        let { payload ->
            val urData = bestilling.brukerData.brevinfo.variableFelter.ur
            val arkivrefs = payload.enheter.flatMap { it.arkivreferanser.map { it.arkivref } }
            val delsumArkivref =
                arkivrefs.associateWith { arkivref ->
                    payload.enheter.flatMap { it.arkivreferanser.filter { it.arkivref == arkivref } }.sumOf { it.refnrsum }
                }

            val arkivRefSumKorrekt =
                urData.arkivRefList.all { arkivRef ->
                    (arkivRef.delsumRef.belop.compareTo(delsumArkivref[arkivRef.nr]) == 0).also {
                        if (!it) logger.error { "Delsum for arkivref ${arkivRef.nr} er feil" }
                    }
                }

            val totalsum = urData.sumTotal.belop
            val payloadTotalsum = payload.enheter.sumOf { it.sumEnhet }

            val totalsumKorrekt =
                (totalsum.compareTo(payloadTotalsum) == 0).also {
                    if (!it) logger.error { "Totalsum er feil for ${urData.orgnummer}. Skulle vært $totalsum, men var $payloadTotalsum" }
                }

            return arkivRefSumKorrekt && totalsumKorrekt
        }
    }
}

enum class NavEnhet(val enhet: String, val navn: String) {
    NOS("8020", "Nav økonomi stønad"),
    NOP("4819", "Nav økonomi pensjon");

    companion object {
        fun navnForEnhet(enhet: String) = entries.find { it.enhet == enhet }?.navn ?: throw Exception("Fant ikke enhet $enhet")
    }
}

@Serializable
data class TrekkKredRapportPdfPayload(
    @Serializable(with = DatoSerializer::class) val rapportSendt: LocalDate,
    @Serializable(with = DatoSerializer::class) val utbetalingsDato: LocalDate,
    @Serializable(with = BelopSerializer::class) val totalsum: BigDecimal,
    val periode: Periode,
    val bedrift: Bedrift,
    val enheter: List<Enhet>,
)

@Serializable
data class Periode(
    @Serializable(with = DatoSerializer::class) val fra: LocalDate,
    @Serializable(with = DatoSerializer::class) val til: LocalDate,
)

@Serializable data class Bedrift(val orgnr: String, val tssid: String, val navn: String, val kontonummer: Bankkonto, val adresse: String)

@Serializable
data class Enhet(
    val navn: String,
    @Serializable(with = BelopSerializer::class) val sumEnhet: BigDecimal,
    val orgnr: OrgNr,
    val arkivreferanser: List<Arkivreferanse>,
)

@Serializable
data class Arkivreferanse(
    val arkivref: String,
    @Serializable(with = BelopSerializer::class) val refnrsum: BigDecimal,
    val trekk: List<Trekk>,
)

@Serializable
data class Trekk(
    val fnr: Fnr,
    val navn: String,
    val saksreferanse: String,
    val periodeFra: String,
    val periodeTil: String,
    val belop: String,
    val tssId: String,
)

object BelopSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    private val symbols = DecimalFormatSymbols(Locale.of("nb", "NO")).apply { groupingSeparator = ' ' }

    private val df = DecimalFormat("#,##0.00", symbols)

    override fun deserialize(decoder: Decoder): BigDecimal =
        when (decoder) {
            is JsonDecoder -> decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal()
            else -> decoder.decodeString().toBigDecimal()
        }

    override fun serialize(encoder: Encoder, value: BigDecimal) =
        when (encoder) {
            is JsonEncoder -> encoder.encodeJsonElement(JsonPrimitive(df.format(value)))
            else -> encoder.encodeString(value.toPlainString())
        }
}

object DatoSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    private var dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    override fun deserialize(decoder: Decoder): LocalDate =
        when (decoder) {
            is JsonDecoder -> LocalDate.parse(decoder.decodeJsonElement().jsonPrimitive.content)
            else -> LocalDate.parse(decoder.decodeString())
        }

    override fun serialize(encoder: Encoder, value: LocalDate) =
        when (encoder) {
            is JsonEncoder -> encoder.encodeJsonElement(JsonPrimitive(value.format(dtf)))
            else -> encoder.encodeString(value.format(dtf))
        }
}
