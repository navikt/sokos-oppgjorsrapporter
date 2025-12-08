package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
import io.micrometer.core.instrument.binder.db.PostgreSQLDatabaseMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
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
        meterBinders =
            listOf(
                UptimeMetrics(),
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                JvmThreadMetrics(),
                ProcessorMetrics(),
                PostgreSQLDatabaseMetrics(dataSource, applicationConfig.postgresProperties.databaseName),
            )
    }

    install(Resources)
}

fun Routing.internalNaisRoutes(
    applicationState: ApplicationState,
    readynessCheck: () -> Boolean = { applicationState.ready },
    alivenessCheck: () -> Boolean = { applicationState.alive },
) {
    val metrics: Metrics by application.dependencies

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
        get("metrics") { call.respondText(metrics.registry.scrape()) }
    }
}
