package no.nav.sokos.oppgjorsrapporter.metrics

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.micrometer.core.instrument.Counter
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

    private val rapportBytesTeller =
        Counter.builder("${NAMESPACE}_rapport_generert_bytes").description("Bytes med genererte rapporter").withRegistry(registry)

    fun tellGenerertRapportVariant(rapportType: RapportType, format: VariantFormat, bytes: Long) =
        rapportBytesTeller.withTags("rapporttype", rapportType.name, "format", format.contentType).increment(bytes.toDouble())

    val pdpKallTimer =
        Timer.builder("${NAMESPACE}_pdp_call_seconds").description("Hvor lang tid tar PDP-kallene våre").withRegistry(registry)

    fun <T> registerGauge(unprefixedName: String, tags: Iterable<Tag> = emptyList(), stateObject: T, valueFunction: (T) -> Double) =
        registry.gauge("${NAMESPACE}_$unprefixedName", tags, stateObject, valueFunction)
}
