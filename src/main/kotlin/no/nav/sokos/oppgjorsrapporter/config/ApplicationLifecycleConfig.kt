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

class ApplicationState(
    var started: Boolean = false,
    var alive: Boolean = true,
    // Skal være false ved oppstart i produksjon (i.e. der skal bakgrunns-jobber faktisk gjøre jobben sin).
    // Ved oppstart som del av automatiserte tester vil denne typisk være true, slik at bakgrunnsjobber ikke forkludrer state for det
    // testene forsøker å teste.  Tester som trenger å skru på bakgrunnsjobber kan flippe denne til 'false' igjen, men bør da huske å flippe
    // den tilbake til 'true' når de er ferdige.
    var disableBackgroundJobs: Boolean,
) {
    private val systems: MutableMap<String, () -> List<String>> = mutableMapOf()

    fun registerSystem(name: String, accessor: () -> List<String>) {
        systems[name] = accessor
    }

    val ready
        get() = started && alive && systems.values.all { it().isEmpty() }

    fun readyErrors(): List<String> = systems.flatMap { (key, value) -> value().map { "$key:\t$it" } }
}
