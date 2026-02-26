package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.math.BigDecimal
import java.net.URI
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.io.bytestring.ByteString
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
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling

class TrekkKredRapportGenerator(
    private val baseUrl: URI,
    private val client: HttpClient,
    private val metrics: Metrics,
    private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}

    fun genererCsvInnhold(bestilling: TrekkKredRapportBestilling): ByteString {
        // return bestilling.tilCSV().encodeToByteString()
        TODO()
    }

    suspend fun genererPdfInnhold(
        bestilling: TrekkKredRapportBestilling,
        arbeidsgiverNavnOgAdresse: OrganisasjonsNavnOgAdresse,
    ): ByteString {
        val pdfGenUrl = baseUrl.resolve("/api/v1/genpdf/oppgjorsrapporter/T14").toURL()
        val response =
            client.post(pdfGenUrl) {
                contentType(ContentType.Application.Json)
                setBody(
                    mapTilTrekkKredRapportPdfPayload(
                        bestilling = bestilling,
                        organisasjonsNavnOgAdresse = arbeidsgiverNavnOgAdresse,
                        rapportSendt = LocalDate.now(clock),
                    )
                )
            }
        metrics.tellEksternEndepunktRequest(response, pdfGenUrl.path)
        return when {
            response.status.isSuccess() -> {
                val pdfInnhold = response.body<ByteArray>()
                ByteString(pdfInnhold)
            }

            else -> {
                val apiError = ApiError(response, "Noe gikk galt ved kall mot PDF-generator tjenesten")

                logger.error { "Feil ved kall mot PDF-generator tjenesten $apiError" }
                throw RuntimeException("Feil ved kall mot PDF-generator tjenesten: $apiError")
            }
        }
    }
}

fun mapTilTrekkKredRapportPdfPayload(
    bestilling: TrekkKredRapportBestilling,
    organisasjonsNavnOgAdresse: OrganisasjonsNavnOgAdresse,
    rapportSendt: LocalDate,
): TrekkKredRapportPdfPayload {
    val urData = bestilling.brukerData.brevinfo.variableFelter.ur
    return TrekkKredRapportPdfPayload(
        rapportSendt = rapportSendt,
        utbetalingsDato = bestilling.dato,
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
        enheter = mapEnheter(bestilling),
    )
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
                totalbelop = enhet.delsum.belop,
                orgnr = urData.orgnummer,
                arkivreferanser =
                    arkivReferanser.map { arkivRef ->
                        val treffListe = arkivRef.enhetList.filter { it.enhetnr == enhet.enhetnr }.flatMap { it.trekkLinjeList }

                        Arkivreferanse(
                            arkivref = arkivRef.nr,
                            refnrsum = arkivRef.delsumRef.belop,
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

@Serializable data class Bedrift(val orgnr: String, val tssid: String, val navn: String, val kontonummer: String, val adresse: String)

@Serializable
data class Enhet(
    val navn: String,
    @Serializable(with = BelopSerializer::class) val totalbelop: BigDecimal,
    val orgnr: String,
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
    val fnr: String,
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
