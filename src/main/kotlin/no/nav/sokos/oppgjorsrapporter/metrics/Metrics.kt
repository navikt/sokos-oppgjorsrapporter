package no.nav.sokos.oppgjorsrapporter.metrics

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingContext
import io.micrometer.core.instrument.Tag
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import javax.jms.Message
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.getBruker
import no.nav.sokos.oppgjorsrapporter.auth.getConsumerOrgnr
import no.nav.sokos.oppgjorsrapporter.auth.tokenValidationContext

class Metrics(val registry: PrometheusMeterRegistry) {
    private val NAMESPACE = "sokos_oppgjorsrapporter"

    private val apiRequestsTeller =
        io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_http_requests")
            .description("Teller antall http requests til Oppgjørsrapporter-API")
            .withRegistry(registry)
    private val apiRequestsSystembrukerTeller =
        io.micrometer.core.instrument.Counter.builder("${NAMESPACE}_http_requests_systembruker")
            .description("Teller antall systembruker-autentiserte requests til Oppgjørsrapporter-API")
            .withRegistry(registry)

    suspend fun tellApiRequest(ctx: RoutingContext) {
        val metode = ctx.call.request.httpMethod
        // Finn "symbolsk" path til requesten, i.e. uten at path-parameters er byttet ut med sine faktiske verdier
        val path =
            ctx.call.pipelineCall.route
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
        val ctx = ctx.tokenValidationContext()
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
