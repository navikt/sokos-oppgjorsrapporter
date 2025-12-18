package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.path
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.binder.db.PostgreSQLDatabaseMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.claimsFor
import no.nav.sokos.oppgjorsrapporter.auth.getBruker
import no.nav.sokos.oppgjorsrapporter.auth.getConsumerOrgnr
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import org.slf4j.event.Level

val TEAM_LOGS_MARKER: Marker? = MarkerFactory.getMarker("TEAM_LOGS")
val commonJsonConfig = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

fun Application.commonConfig() {
    val applicationConfig: PropertiesConfig.Configuration by dependencies
    val dataSource: DataSource by dependencies
    val metrics: Metrics by dependencies

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
        disableDefaultColors()
    }

    install(ContentNegotiation) { json(commonJsonConfig) }

    install(MicrometerMetrics) {
        registry = metrics.registry
        distributionStatisticConfig = DistributionStatisticConfig.Builder().percentilesHistogram(true).build()
        meterBinders += listOf(PostgreSQLDatabaseMetrics(dataSource, applicationConfig.postgresProperties.databaseName))

        timers { call, _ ->
            val validationCtx = call.principal<TokenValidationContextPrincipal>()?.context
            val bruker = validationCtx?.getBruker()?.getOrNull()
            tag("auth_type", bruker?.authType ?: "unknown")
            tag(
                "authorized_party",
                when (bruker) {
                    is Systembruker -> validationCtx.getConsumerOrgnr()
                    is EntraId ->
                        (validationCtx.claimsFor(AuthenticationType.INTERNE_BRUKERE_AZUREAD_JWT).get("azp_name") as? String) ?: "unknown"
                    null -> "unknown"
                },
            )
        }
    }

    install(Resources)
}

fun Routing.internalNaisRoutes(
    applicationState: ApplicationState,
    readynessCheck: () -> Boolean = { applicationState.ready },
    alivenessCheck: () -> Boolean = { applicationState.alive },
) {
    val metrics: Metrics by application.dependencies
    val rapportService: RapportService by application.dependencies

    route("internal") {
        get("isAlive") {
            when (alivenessCheck()) {
                true -> call.respondText { "I'm alive :)" }
                else -> call.respondText(text = "I'm dead x_x", status = HttpStatusCode.InternalServerError)
            }
        }
        get("isReady") {
            when (readynessCheck()) {
                true -> call.respondText { "I'm ready! :)" }
                else ->
                    call.respondText(text = applicationState.readyErrors().joinToString("\n"), status = HttpStatusCode.InternalServerError)
            }
        }
        get("metrics") {
            // For "vanlige" gauges kan man registrere en "valueFunction" som produserer gaugens numeriske verdi - men for MultiGauge ser
            // det ikke ut til å finnes noe tilsvarende for å oppdatere alle multi-gaugens verdier (eller "rows") ved å kalle én funksjon.
            // Vi gjør derfor slik "hent ut verdiene for alle dimensjoner av en multi-gauge i ett database-søk" her,°◊ og oppdaterer
            // multi-gaugen med resultatene før registry-scraping rapporterer verdiene.
            rapportService
                .metrikkForUprosesserteBestillinger()
                .map { (tags, verdi) -> MultiGauge.Row.of(tags, verdi) }
                .let { rows -> metrics.oppdaterUprosesserteBestillinger(rows) }
            call.respondText(metrics.registry.scrape())
        }
    }
}
