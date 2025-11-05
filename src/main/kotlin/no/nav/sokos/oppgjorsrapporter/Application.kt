package no.nav.sokos.oppgjorsrapporter

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.resources.Resources
import io.ktor.util.AttributeKey
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.DefaultAuthClient
import no.nav.sokos.oppgjorsrapporter.auth.NoOpAuthClient
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.DatabaseConfig
import no.nav.sokos.oppgjorsrapporter.config.DatabaseMigrator
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.config.applicationLifecycleConfig
import no.nav.sokos.oppgjorsrapporter.config.commonConfig
import no.nav.sokos.oppgjorsrapporter.config.configFrom
import no.nav.sokos.oppgjorsrapporter.config.createDataSource
import no.nav.sokos.oppgjorsrapporter.config.routingConfig
import no.nav.sokos.oppgjorsrapporter.config.securityConfig
import no.nav.sokos.oppgjorsrapporter.jobs.RapportMottak
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer
import no.nav.sokos.oppgjorsrapporter.pdp.AltinnPdpService
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService

private val logger = KotlinLogging.logger {}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module)
        .also { it.addShutdownHook { it.stop(shutdownGracePeriod = 3, shutdownTimeout = 5, timeUnit = TimeUnit.SECONDS) } }
        .start(true)
}

fun Application.module(appConfig: ApplicationConfig = environment.config) {
    val config = resolveConfig(appConfig)

    // Gjør dette tidlig, så Micrometer sitt PrometheusMeterRegistry ikke skriker om at
    // "A MeterFilter is being configured after a Meter has been registered to this registry"
    // fordi createDataSource() registrerer Meters
    commonConfig()

    val applicationState = ApplicationState()
    DatabaseMigrator(createDataSource(config.postgresProperties.adminJdbcUrl), applicationState)

    DatabaseConfig.init(config)

    install(Resources)

    dependencies {
        provide<DataSource> { DatabaseConfig.dataSource }
        provide(RapportRepository::class)
        provide(RapportService::class)
        provide<AuthClient> {
            if (config.applicationProperties.profile == PropertiesConfig.Profile.LOCAL) NoOpAuthClient()
            else DefaultAuthClient(config.securityProperties.tokenEndpoint, config.securityProperties.maskinportenProperties.altinn3BaseUrl)
        }
        provide<PdpService> { AltinnPdpService(config.securityProperties, resolve()) }

        if (config.mqConfiguration.enabled) {
            config.mqConfiguration.queues.forEach { inQueue ->
                provide<MqConsumer>("mq.consumer.${inQueue.key}") { MqConsumer(config.mqConfiguration, inQueue.queueName) }
                provide("mq.consumejob.${inQueue.key}") {
                        val mqErrors = mutableListOf<String>()
                        applicationState.registerSystem("MQ") { mqErrors }

                        val exceptionHandler = CoroutineExceptionHandler { _, e ->
                            logger.error(TEAM_LOGS_MARKER, e) { "Mottatt alvorlig exception i MQ-subsystemet" }
                            // Ved å legge til en feil på et registrert system, flippes applicationState.ready til `false`
                            mqErrors.add(e.toString())
                            applicationState.alive = false
                        }

                        with(CoroutineScope(Dispatchers.IO + exceptionHandler + MDCContext() + SupervisorJob())) {
                            launch { RapportMottak(applicationState, resolve()).run() }
                        }
                    }
                    .cleanup { it.cancel() }
            }
        }
    }

    applicationLifecycleConfig(applicationState)
    securityConfig(config)
    routingConfig(applicationState)
}

val ConfigAttributeKey = AttributeKey<PropertiesConfig.Configuration>("config")

fun Application.config(): PropertiesConfig.Configuration = this.attributes[ConfigAttributeKey]

fun Application.resolveConfig(appConfig: ApplicationConfig = environment.config): PropertiesConfig.Configuration =
    if (attributes.contains(ConfigAttributeKey)) {
        // Bruk config hvis den allerede er satt
        this.config()
    } else {
        configFrom(appConfig).also { attributes.put(ConfigAttributeKey, it) }
    }
