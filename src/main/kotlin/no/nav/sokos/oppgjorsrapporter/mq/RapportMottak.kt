@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class, BigDecimalSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.mq

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer

const val LINJESKIFT = "\r\n"

class RapportMottak(private val refusjonMqConsumer: MqConsumer, private val rapportService: RapportService) {
    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        while (true) {
            currentCoroutineContext().ensureActive()
            hentBestilling { process(it) }
        }
    }

    fun process(melding: Melding) {
        val bestilling = RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(melding.data)
        logger.info(TEAM_LOGS_MARKER) { "Hentet rapport-bestilling: $bestilling" }
        rapportService.lagreBestilling(melding.kilde, RapportType.K27, melding.data)
    }

    private suspend fun hentBestilling(block: suspend (Melding) -> Unit) {
        refusjonMqConsumer.receive()?.let { melding ->
            logger.info(TEAM_LOGS_MARKER) { "Melding mottatt: $melding" }
            try {
                block(melding)
                refusjonMqConsumer.commit()
            } catch (ex: Exception) {
                refusjonMqConsumer.rollback()
                logger.error(TEAM_LOGS_MARKER, ex) { "Noe gikk galt; lesing av meldingen er rullet tilbake (kanskje til BOQ)" }
            }
        }
    }
}

@Serializable
data class RefusjonsRapportBestilling(val header: Header, val datarec: List<Data>) {
    fun tilCSV(): String {
        return datarec.joinToString(LINJESKIFT) { byggCsvRad(header, it) } + LINJESKIFT
    }

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
     * 10. belop (11 tegn) - Beløp i ører (10 siffer + fortegn). Kreditbeløp (negative) får '-' på slutten, debetbeløp får ' ' (mellomrom)
     *     på slutten
     * 11. hardkodetFelt (11 tegn) - Hardkodet til "0000000000 " (10 nuller + mellomrom)
     * 12. maxDato (8 siffer) - Maks dato på format ÅÅÅÅMMDD. Settes til "00000000" hvis feltet mangler
     *
     * @param header Header-informasjon fra refusjonsrapporten
     * @param data Data-rad fra refusjonsrapporten
     * @return CSV-streng som er likt det vi leverer til leverandørene med Altinn2 løsning
     */
    private fun byggCsvRad(header: Header, data: Data): String {
        val hardkodetFelt = "0000000000 "

        return listOf(
                data.navenhet,
                header.orgnr,
                data.bedriftsnummer,
                data.kode,
                data.fnr,
                formaterDatoForCsv(data.fraDato),
                header.bankkonto,
                formaterNavn(data.navn),
                formaterDatoForCsv(data.tilDato),
                formaterBeløp(data.belop),
                hardkodetFelt,
                formaterDatoForCsv(data.maxDato),
            )
            .joinToString(";")
    }

    private fun formaterDatoForCsv(dato: LocalDate?): String {
        return dato?.format(DateTimeFormatter.BASIC_ISO_DATE) ?: "0".repeat(8)
    }

    /**
     * Formaterer et beløp til CSV-formatet.
     *
     * Beløpet konverteres til ører (multiplisert med 100) og formateres som en streng med totalt 11 tegn:
     * - De første 10 tegnene er numeriske, fylt med ledende nuller om nødvendig.
     * - Det siste tegnet er enten '-' for negative beløp (kredit) eller ' ' (mellomrom) for positive beløp (debet).
     *
     * @param belop Beløpet som skal formateres
     * @return Formaterte beløp som streng i CSV-format
     */
    private fun formaterBeløp(belop: BigDecimal): String {
        val beløpIØrer: Long = (belop * BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val absolutBeløp = abs(beløpIØrer)
        val dkSuffiks = if (beløpIØrer < 0) "-" else " "
        return "${absolutBeløp.toString().padStart(10, '0')}$dkSuffiks"
    }

    /** Formaterer navnet ved å erstatte eventuelle semikolon, quote og linjeskift med mellomrom og padde det til 25 tegn. */
    private fun formaterNavn(navn: String): String {
        return navn.replace(Regex("[\";\n\r]+"), " ").trim().take(25).padEnd(25, ' ')
    }

    companion object {
        val json = Json { explicitNulls = false }
    }
}

@Serializable
data class Header(
    val orgnr: String,
    val bankkonto: String,
    val sumBelop: BigDecimal,
    val valutert: LocalDate,
    // Antall elementer i `datarec: List<Data>`.  Trengs egentlig ikke av vår applikasjon, men JSON-genereringen på sender-siden har ikke
    // noen enkel måte å utelate den på.
    val linjer: Long,
)

@Serializable
data class Data(
    val navenhet: Int,
    val bedriftsnummer: String,
    val kode: String,
    val tekst: String,
    val fnr: String,
    val navn: String,
    val belop: BigDecimal,
    val fraDato: LocalDate?,
    val tilDato: LocalDate?,
    val maxDato: LocalDate?,
)
