package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.plugins.di.dependencies

fun Application.applicationLifecycleConfig() {
    val applicationState: ApplicationState by dependencies
    monitor.subscribe(ApplicationStarted) { applicationState.started = true }
    monitor.subscribe(ApplicationStopPreparing) { applicationState.started = false }
}

class ApplicationState(var started: Boolean = false, var alive: Boolean = true) {
    private val systems: MutableMap<String, () -> List<String>> = mutableMapOf()

    fun registerSystem(name: String, accessor: () -> List<String>) {
        systems[name] = accessor
    }

    val ready
        get() = started && alive && systems.values.all { it().isEmpty() }

    fun readyErrors(): List<String> = systems.flatMap { (key, value) -> value().map { "$key:\t$it" } }
}
