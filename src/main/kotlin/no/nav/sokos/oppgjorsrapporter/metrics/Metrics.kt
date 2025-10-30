package no.nav.sokos.oppgjorsrapporter.metrics

import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingContext
import io.micrometer.core.instrument.Tag
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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
    val tags = mutableListOf<Tag>(Tag.of("ressurs", path), Tag.of("metode", metode.value))
    ctx.getBruker().onSuccess {
        when (it) {
            is Systembruker ->
                tags.addAll(
                    listOf(
                        Tag.of("auth", "systembruker"),
                        Tag.of("systembruker_orgnr", it.userOrg.raw),
                        Tag.of("system_id", it.systemId),
                        Tag.of("consumer_orgnr", ctx.getConsumerOrgnr()),
                    )
                )

            is EntraId -> tags.add(Tag.of("auth", "azure"))
        }
    }
    apiRequestsTeller.withTags(tags).increment()
}
