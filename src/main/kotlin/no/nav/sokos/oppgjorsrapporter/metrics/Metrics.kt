package no.nav.sokos.oppgjorsrapporter.metrics

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.micrometer.core.instrument.Tag
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import javax.jms.Message

class Metrics(val registry: PrometheusMeterRegistry) {
    private val NAMESPACE = "sokos_oppgjorsrapporter"

    private val clientRequestsTeller =
        io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_client_http_requests")
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

    private val mottatteJmsMeldingerTeller =
        io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_received_jms_messages")
            .description("Teller antall mottatte JMS-meldinger")
            .withRegistry(registry)

    fun tellMottak(msg: Message, queueName: String) {
        mottatteJmsMeldingerTeller
            .withTags(
                listOf(
                    Tag.of("queue", queueName),
                    Tag.of("class", msg.javaClass.canonicalName),
                    Tag.of("type", msg.jmsType ?: "null"),
                    Tag.of("destination", msg.jmsDestination?.toString() ?: "null"),
                    Tag.of("reply_to", msg.jmsReplyTo?.toString() ?: "null"),
                    Tag.of("priority", msg.jmsPriority.toString()),
                )
            )
            .increment()
    }

    fun <T> registerGauge(unprefixedName: String, tags: Iterable<Tag> = emptyList(), stateObject: T, valueFunction: (T) -> Double) =
        registry.gauge("${NAMESPACE}_$unprefixedName", tags, stateObject, valueFunction)
}
