package no.nav.sokos.oppgjorsrapporter.metrics

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.rapport.VariantFormat

class Metrics(val registry: PrometheusMeterRegistry) {
    private val NAMESPACE = "sokos_oppgjorsrapporter"

    private val clientRequestsTeller =
        Counter.builder("${NAMESPACE}_client_http_requests")
            .description("Teller antall http requests til eksterne APIer")
            .withRegistry(registry)

    fun tellEksternEndepunktRequest(response: HttpResponse, endepunkt: String) {
        clientRequestsTeller
            .withTags(
                listOf(
                    Tag.of("host", response.request.url.host),
                    Tag.of("endepunkt", endepunkt),
                    Tag.of("metode", response.request.method.value),
                    Tag.of("status_code", response.status.value.toString()),
                )
            )
            .increment()
    }

    private val mottatteBestillingerTeller =
        Counter.builder("${NAMESPACE}_bestilling_mottatt_count").description("Antall mottatte rapport-bestillinger").withRegistry(registry)
    private val mottatteBestillingRaderTeller =
        Counter.builder("${NAMESPACE}_bestilling_mottatt_rader")
            .description("Antall mottatte rapport-bestillings-rader")
            .withRegistry(registry)

    fun tellMottak(rapportType: RapportType, kilde: String, rader: Int) =
        listOf(Tag.of("kilde", kilde), Tag.of("rapporttype", rapportType.name)).let { tags ->
            mottatteBestillingerTeller.withTags(tags).increment()
            mottatteBestillingRaderTeller.withTags(tags).increment(rader.toDouble())
        }

    private val uprosesserteBestillingerGauge =
        MultiGauge.builder("${NAMESPACE}_bestilling_uprosessert_count")
            .description("Antall uprosesserte bestillinger som finnes i databasen")
            .register(registry)

    fun oppdaterUprosesserteBestillinger(rows: Iterable<MultiGauge.Row<Number>>) = uprosesserteBestillingerGauge.register(rows, true)

    private val prosesseringAvBestillingTeller =
        Counter.builder("${NAMESPACE}_bestilling_prosessert_count")
            .description("Antall forsøkte prosesseringer av rapport-bestillinger")
            .withRegistry(registry)

    fun tellBestillingsProsessering(rapportType: RapportType, kilde: String, feilet: Boolean) =
        prosesseringAvBestillingTeller.withTags("rapporttype", rapportType.name, "kilde", kilde, "feilet", feilet.toString()).increment()

    val rapportGenerertTimer =
        Timer.builder("${NAMESPACE}_rapport_generert_seconds")
            .description("Tid brukt på å generere rapporter")
            .publishPercentileHistogram()
            .withRegistry(registry)

    private val rapportBytesTeller =
        Counter.builder("${NAMESPACE}_rapport_generert_bytes").description("Bytes med genererte rapporter").withRegistry(registry)

    fun tellGenerertRapportVariant(rapportType: RapportType, format: VariantFormat, bytes: Long) =
        rapportBytesTeller.withTags("rapporttype", rapportType.name, "format", format.contentType).increment(bytes.toDouble())

    private val pdpKallTeller = Counter.builder("${NAMESPACE}_pdp_call_count").description("Antall kall gjort mot PDP").register(registry)
    private val pdpDecisionsTeller =
        Counter.builder("${NAMESPACE}_pdp_decision_count").description("Antall avgjørelser PDP er bedt om").register(registry)

    fun tellPdpKall(decisionCount: Int) {
        pdpKallTeller.increment()
        pdpDecisionsTeller.increment(decisionCount.toDouble())
    }

    val pdpKallTimer =
        Timer.builder("${NAMESPACE}_pdp_call_seconds")
            .description("Tid brukt på PDP-kall")
            .publishPercentileHistogram()
            .withRegistry(registry)

    val rapportSokReturnertAntall =
        DistributionSummary.builder("${NAMESPACE}_api_rapport_sok_response_count")
            .description("Antallet rapporter søke-APIet returnerer per respons")
            .publishPercentileHistogram()
            .withRegistry(registry)

    // Siden Timer måler i nanosekunder, brukes den ikke her; det kunne gitt overflow-trøbbel når summen av alle timer-registreringer når
    // Long.MAX_VALUE (eller ~292.3 år)
    val rapportAlderVedForsteNedlasting =
        DistributionSummary.builder("${NAMESPACE}_rapport_alder_ved_forste_nedlasting_seconds")
            .description("Tid fra rapport-generering til første nedlasting av ekstern bruker")
            .publishPercentileHistogram()
            .withRegistry(registry)

    private val rapporterGauge =
        MultiGauge.builder("${NAMESPACE}_rapport_count").description("Antall rapporter i databasen").register(registry)

    fun oppdaterRapportData(rows: Iterable<MultiGauge.Row<Number>>) = rapporterGauge.register(rows, true)

    // Hjelpefunksjon for å kunne gjøre timing av suspend-funksjoner:
    suspend fun <T> coRecord(timer: (Result<T>) -> Timer, f: suspend () -> T): T =
        Timer.start(registry).let { sample -> runCatching { f() }.also { sample.stop(timer(it)) }.getOrThrow() }
}
