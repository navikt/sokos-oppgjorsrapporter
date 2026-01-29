@file:UseSerializers(BigDecimalSerializer::class, LocalDateSomNorskDatoSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.rapport.generator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.groupBy
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.ereg.OrganisasjonsNavnOgAdresse
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.Data
import no.nav.sokos.oppgjorsrapporter.mq.Header
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.tilCSV
import no.nav.sokos.oppgjorsrapporter.rapport.generator.LocalDateSomNorskDatoSerializer.norskDatoFormatter
import no.nav.sokos.oppgjorsrapporter.serialization.AsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer

class RapportGenerator(private val baseUrl: URI, private val client: HttpClient, private val metrics: Metrics, private val clock: Clock) {
    private val logger = KotlinLogging.logger {}

    fun genererCsvInnhold(bestilling: RefusjonsRapportBestilling): ByteString {
        return bestilling.tilCSV().encodeToByteString()
    }

    suspend fun genererPdfInnhold(
        bestilling: RefusjonsRapportBestilling,
        arbeidsgiverNavnOgAdresse: OrganisasjonsNavnOgAdresse,
    ): ByteString {
        return run {
            val payload =
                RefusjonsRapportPdfPayload(
                    bestilling = bestilling,
                    organisasjonsNavnOgAdresse = arbeidsgiverNavnOgAdresse,
                    rapportSendt = LocalDate.now(clock),
                )
            val pdfGenUrl = baseUrl.resolve("/api/v1/genpdf/oppgjorsrapporter/refusjon-arbg-sortert-etter-ytelse").toURL()
            val response =
                client.post(pdfGenUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            metrics.tellEksternEndepunktRequest(response, pdfGenUrl.path)

            when {
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

    companion object : HttpClientSetup {
        override val jsonConfig: Json = commonJsonConfig
    }
}

object CsvGenerering {
    const val LINJESKIFT = "\r\n"

    internal fun RefusjonsRapportBestilling.tilCSV(): String {
        return datarec.joinToString(separator = LINJESKIFT, postfix = LINJESKIFT) { RefArbg.byggCsvRad(header, it) }
    }

    private object RefArbg {
        /**
         * Bygger en CSV-rad basert på header og data fra refusjonsrapporten.
         *
         * CSV-formatet består av følgende felter separert med semikolon (;) i følgende rekkefølge:
         * 1. navenhet (4 siffer) - Numerisk enhetsnummer
         * 2. orgnr (9 siffer) - Organisasjonsnummer (hovedenhet)
         * 3. bedriftsnummer (9 siffer) - Bedriftens registreringsnummer (undersenhet eller det samme som hovedenhet)
         * 4. kode (1 tegn) - Alfanumerisk kode for yttelsen
         * 5. fnr (11 siffer) - Fødselsnummer
         * 6. fraDato (8 siffer) - Fra dato på format ÅÅÅÅMMDD
         * 7. bankkonto (11 tegn) - Norsk bankkontonummer
         * 8. navn (25 tegn) - Navn på person, padding med mellomrom til høyre hvis kortere. Semikolon erstattes med mellomrom
         * 9. tilDato (8 siffer) - Til dato på format ÅÅÅÅMMDD
         * 10. belop (11 tegn) - Beløp i ører (10 siffer + fortegn). Kreditbeløp (negative) får '-' på slutten, debetbeløp får ' '
         *     (mellomrom) på slutten
         * 11. hardkodetFelt (11 tegn) - Hardkodet til "0000000000 " (10 nuller + mellomrom)
         * 12. maxDato (8 siffer) - Maks dato på format ÅÅÅÅMMDD. Settes til "00000000" hvis feltet mangler
         *
         * @param header Header-informasjon fra refusjonsrapporten
         * @param data Data-rad fra refusjonsrapporten
         * @return CSV-streng som er likt det vi leverer til leverandørene med Altinn2 løsning
         */
        fun byggCsvRad(header: Header, data: Data): String {
            val hardkodetFelt = "0000000000 "

            return listOf(
                    data.navenhet,
                    header.orgnr,
                    data.bedriftsnummer,
                    data.kode,
                    data.fnr,
                    data.fraDato.formatterDatoForCsv(),
                    header.bankkonto,
                    data.navn.formatterAnsattNavnForCsv(),
                    data.tilDato.formatterDatoForCsv(),
                    data.belop.formatterBeløpForCsv(),
                    hardkodetFelt,
                    data.maxDato.formatterDatoForCsv(),
                )
                .joinToString(";")
        }

        private fun LocalDate?.formatterDatoForCsv(): String {
            return this?.format(DateTimeFormatter.BASIC_ISO_DATE) ?: "0".repeat(8)
        }

        /** Formatterer navnet ved å erstatte eventuelle semikolon, quote og linjeskift med mellomrom og padde det til 25 tegn. */
        private fun String.formatterAnsattNavnForCsv(): String {
            return this.replace(Regex("[\";\n\r]+"), " ").trim().take(25).padEnd(25, ' ')
        }

        /**
         * Formaterer et beløp til CSV-formatet.
         *
         * Beløpet konverteres til ører (multiplisert med 100) og formateres som en streng med totalt 11 tegn:
         * - De første 10 tegnene er numeriske, fylt med ledende nuller om nødvendig.
         * - Det siste tegnet er enten '-' for negative beløp (kredit) eller ' ' (mellomrom) for positive beløp (debet).
         *
         * @return Formaterte beløp som streng i CSV-format
         */
        private fun BigDecimal.formatterBeløpForCsv(): String {
            val beløpIØrer: Long = (this * BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            val absolutBeløp = kotlin.math.abs(beløpIØrer)
            val dkSuffiks = if (beløpIØrer < 0) "-" else " "
            return "${absolutBeløp.toString().padStart(10, '0')}$dkSuffiks"
        }
    }
}

@Serializable
data class RefusjonsRapportPdfPayload(
    val rapportSendt: LocalDate,
    val utbetalingsDato: LocalDate,
    val totalsum: Belop,
    val bedrift: Bedrift,
    val ytelser: List<Ytelse>,
) {
    /**
     * Konverterer refusjonsrapportens header og data til et PDF-payload-objekt.
     *
     * @param rapportSendt Datoen rapporten ble sendt. Standardverdi er dagens dato. Lagt til for fleksibilitet ved testing.
     * @param organisasjonsNavnOgAdresse Informasjon om hovedenheten som mottar rapporten.
     * @return RefusjonsRapportPdfPayload som representerer dataene i et format egnet for PDF-generering.
     */
    constructor(
        bestilling: RefusjonsRapportBestilling,
        organisasjonsNavnOgAdresse: OrganisasjonsNavnOgAdresse,
        rapportSendt: LocalDate,
    ) : this(
        rapportSendt = rapportSendt,
        utbetalingsDato = bestilling.header.valutert,
        totalsum = Belop(bestilling.header.sumBelop),
        bedrift =
            with(bestilling) {
                Bedrift(
                    orgnr = OrgNummer(header.orgnr),
                    navn = organisasjonsNavnOgAdresse.navn,
                    kontonummer = Kontonummer(header.bankkonto),
                    adresse = organisasjonsNavnOgAdresse.adresse,
                )
            },
        ytelser =
            bestilling.datarec
                .map { data ->
                    data.tekst to
                        Postering(
                            orgnr = OrgNummer(data.bedriftsnummer),
                            fnr = Fodselsnummer(data.fnr),
                            navn = data.navn,
                            periodeFra = data.fraDato,
                            periodeTil = data.tilDato,
                            maksDato = data.maxDato,
                            belop = Belop(data.belop),
                        )
                }
                .groupBy({ it.first }) { it.second }
                .map { (ytelse, posteringer) ->
                    Ytelse(
                        ytelse = ytelse,
                        totalbelop = Belop(posteringer.sumOf { it.belop.verdi }),
                        posteringer.sortedWith(compareBy({ it.orgnr.verdi }, { it.fnr.verdi }, { it.periodeFra })),
                    )
                }
                .sortedBy { it.ytelse },
    )

    @Serializable data class Bedrift(val orgnr: OrgNummer, val navn: String, val kontonummer: Kontonummer, val adresse: String)

    @Serializable data class Ytelse(val ytelse: String, val totalbelop: Belop, val posteringer: List<Postering>)

    @Serializable
    data class Postering(
        val orgnr: OrgNummer,
        val fnr: Fodselsnummer,
        val navn: String,
        val periodeFra: LocalDate?,
        val periodeTil: LocalDate?,
        val maksDato: LocalDate?,
        val belop: Belop,
    )
}

object LocalDateSomNorskDatoSerializer :
    AsStringSerializer<LocalDate>(
        serialName = "utbetaling.pengeflyt.kotlinx.LocalDateSomNorskDatoSerializer",
        { input -> LocalDate.parse(input, norskDatoFormatter) },
    ) {
    override fun serialize(encoder: Encoder, value: LocalDate) {
        norskDatoFormatter.format(value).let(encoder::encodeString)
    }

    private val norskDatoFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
}

@Serializable
@ConsistentCopyVisibility
data class OrgNummer private constructor(val verdi: String, val formattert: String) {
    constructor(
        orgnr: String
    ) : this(verdi = orgnr, formattert = orgnr.windowedSequence(size = 3, step = 3, partialWindows = true).joinToString(" ")) {
        require(orgnr.length == 9)
        require(orgnr.all { it.isDigit() })
    }
}

@Serializable
@ConsistentCopyVisibility
data class Kontonummer private constructor(val verdi: String, val formattert: String) {
    constructor(
        kontonr: String
    ) : this(verdi = kontonr, formattert = with(kontonr) { listOf(take(4), drop(4).take(2), drop(4 + 2)) }.joinToString(" ")) {
        require(kontonr.length == 11)
        require(kontonr.all { it.isDigit() })
    }
}

@Serializable
@ConsistentCopyVisibility
data class Belop private constructor(val verdi: BigDecimal, val formattert: String) {
    constructor(
        belop: BigDecimal
    ) : this(
        verdi = belop.setScale(2, RoundingMode.HALF_UP),
        formattert =
            NumberFormat.getNumberInstance(Locale.of("no", "NO"))
                .apply {
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }
                .format(belop),
    )
}

@Serializable
@ConsistentCopyVisibility
data class Fodselsnummer private constructor(val verdi: String, val formattert: String) {
    constructor(fnr: String) : this(fnr, with(fnr) { listOf(take(6), drop(6)) }.joinToString(" ")) {
        require(fnr.length == 11)
        require(fnr.all { it.isDigit() })
    }
}
