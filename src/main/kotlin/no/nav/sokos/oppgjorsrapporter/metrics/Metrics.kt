package no.nav.sokos.oppgjorsrapporter.metrics

import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingContext
import io.micrometer.core.instrument.Tag
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import javax.jms.Message
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.getBruker
import no.nav.sokos.oppgjorsrapporter.auth.getConsumerOrgnr
import no.nav.sokos.oppgjorsrapporter.auth.tokenValidationContext

private const val NAMESPACE = "sokos_oppgjorsrapporter"

val prometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

private val apiRequestsTeller =
    io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_http_requests")
        .description("Teller antall http requests til Oppgjørsrapporter-API")
        .withRegistry(prometheusMeterRegistry)
private val apiRequestsSystembrukerTeller =
    io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_http_requests_systembruker")
        .description("Teller antall systembruker-autentiserte requests til Oppgjørsrapporter-API")
        .withRegistry(prometheusMeterRegistry)

suspend fun RoutingContext.tellApiRequest() {
    val metode = call.request.httpMethod
    // Finn "symbolsk" path til requesten, i.e. uten at path-parameters er byttet ut med sine faktiske verdier
    val path =
        call.pipelineCall.route
            .toString()
            // Her har vi noe som kan se slik ut: "/(authenticate INTERNE_BRUKERE_AZUREAD_JWT,
            // API_INTEGRASJON_ALTINN_SYSTEMBRUKER)/api/rapport/v1/{id}/[inkluderArkiverte?]/[orgnr?]/(method:GET)"
            .split("/")
            .filterNot {
                // Dropp "(authenticate ...)"-segmenter og "(method:...)"-segmenter
                it.startsWith("(") ||
                    // Dropp "[queryParam]"-segmenter
                    it.startsWith("[")
            }
            .joinToString("/")
    val ctx = tokenValidationContext()
    val basisTags = listOf<Tag>(Tag.of("ressurs", path), Tag.of("metode", metode.value))
    val auth =
        ctx.getBruker()
            .fold(
                {
                    when (it) {
                        is Systembruker -> {
                            val systembrukerTags =
                                listOf(
                                    Tag.of("systembruker_orgnr", it.userOrg.raw),
                                    Tag.of("system_id", it.systemId),
                                    Tag.of("consumer_orgnr", ctx.getConsumerOrgnr()),
                                )
                            apiRequestsSystembrukerTeller.withTags(basisTags.plus(systembrukerTags)).increment()
                            "systembruker"
                        }
                        is EntraId -> "azure"
                    }
                },
                { "unknown" },
            )
    apiRequestsTeller.withTags(basisTags.plus(Tag.of("auth", auth))).increment()
}

private val mottatteJmsMeldingerTeller =
    io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_received_jms_messages")
        .description("Teller antall mottatte JMS-meldinger")
        .withRegistry(prometheusMeterRegistry)

fun Message.tellMottak(queueName: String) {
    mottatteJmsMeldingerTeller
        .withTags(
            listOf(
                Tag.of("queue", queueName),
                Tag.of("class", this.javaClass.canonicalName),
                Tag.of("type", this.jmsType ?: "null"),
                Tag.of("destination", this.jmsDestination?.toString() ?: "null"),
                Tag.of("reply_to", this.jmsReplyTo?.toString() ?: "null"),
                Tag.of("priority", this.jmsPriority.toString()),
            )
        )
        .increment()
}

fun <T> registerGauge(unprefixedName: String, tags: Iterable<Tag> = emptyList(), stateObject: T, valueFunction: (T) -> Double) =
    prometheusMeterRegistry.gauge("${NAMESPACE}_$unprefixedName", tags, stateObject, valueFunction)
