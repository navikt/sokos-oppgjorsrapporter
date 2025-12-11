package no.nav.sokos.oppgjorsrapporter

import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.dependencies
import io.ktor.util.AttributeKey
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Clock
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
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.innhold.generator.HttpClientFactory
import no.nav.sokos.oppgjorsrapporter.innhold.generator.RefusjonArbeidsgiverInnholdGenerator
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer
import no.nav.sokos.oppgjorsrapporter.mq.RapportMottak
import no.nav.sokos.oppgjorsrapporter.pdp.AltinnPdpService
import no.nav.sokos.oppgjorsrapporter.pdp.LocalhostPdpService
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.BestillingProsessor
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

private val logger = KotlinLogging.logger {}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module)
        .also { it.addShutdownHook { it.stop(shutdownGracePeriod = 3, shutdownTimeout = 5, timeUnit = TimeUnit.SECONDS) } }
        .start(true)
}

fun Application.module(appConfig: ApplicationConfig = environment.config, clock: Clock = Clock.systemUTC()) {
    // For å tillate tester å overstyre dependencies, bør man først
    // 1. gjøre `provide` av en instans med passende type og evt. navn,
    // for så evt.
    // 2. å hente ut den providede instansen fra DI-registeret
    // før man bruker instansen lenger ned.
    dependencies {
        provide { clock }
        provide { this@module.resolveConfig(appConfig) }
        val config: PropertiesConfig.Configuration by this

        provide { ApplicationState(disableBackgroundJobs = config.applicationProperties.disableBackgroundJobs) }
        val applicationState: ApplicationState by this

        // Vi vil helst ikke registrere denne dataSourcen i DI-registeret, siden den ikke skal brukes til noe annet enn Flyway.  Samtidig er
        // det fint om testene husker å lukke denne dataSourcen også når en testApplication avsluttes; se .cleanup() på
        // DataSource-registreringen under.
        val adminDataSource = createDataSource(config.postgresProperties.adminJdbcUrl)
        DatabaseMigrator(adminDataSource, applicationState)
        DatabaseConfig.init(config)

        provide { Metrics(PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) }
        provide<DataSource> { DatabaseConfig.dataSource }.cleanup { adminDataSource.close() }
        provide(RapportRepository::class)
        provide(RapportService::class)
        provide(HttpClientFactory::class)
        provide<HttpClient> {
                val httpClientFactory: HttpClientFactory = resolve()
                httpClientFactory.createHttpClient()
            }
            .cleanup { it.close() }

        provide<EregService> { EregService(config.innholdGeneratorProperties.eregBaseUrl, resolve(), resolve()) }
        provide<RefusjonArbeidsgiverInnholdGenerator> {
            RefusjonArbeidsgiverInnholdGenerator(config.innholdGeneratorProperties.pdfGenBaseUrl, resolve(), resolve(), resolve())
        }

        if (config.applicationProperties.profile == PropertiesConfig.Profile.LOCAL) {
            provide<AuthClient> { NoOpAuthClient() }
            provide<PdpService> { LocalhostPdpService }
        } else {
            provide<AuthClient> {
                DefaultAuthClient(config.securityProperties.tokenEndpoint, config.securityProperties.maskinportenProperties.altinn3BaseUrl)
            }
            provide<PdpService> { AltinnPdpService(config.securityProperties, resolve()) }
        }

        if (config.mqConfiguration.enabled) {
            val mqErrors = mutableListOf<String>()
            applicationState.registerSystem("MQ") { mqErrors }

            val exceptionHandler = CoroutineExceptionHandler { _, e ->
                logger.error(TEAM_LOGS_MARKER, e) { "Mottatt alvorlig exception i MQ-subsystemet" }
                // Ved å legge til en feil på et registrert system, flippes applicationState.ready til `false`
                mqErrors.add(e.toString())
                applicationState.alive = false
            }

            config.mqConfiguration.queues.forEach { inQueue ->
                val consumerKey = "mq.consumer.${inQueue.key}"
                provide(consumerKey) { MqConsumer(config.mqConfiguration, inQueue.queueName, resolve()) }

                val mottakKey = "mq.mottak.${inQueue.key}"
                provide(mottakKey) { RapportMottak(resolve(consumerKey), resolve()) }

                val job =
                    with(CoroutineScope(Dispatchers.IO + exceptionHandler + MDCContext() + SupervisorJob())) {
                        launch { resolve<RapportMottak>(mottakKey).run() }
                    }
                provide("mq.consumejob.${inQueue.key}") { job }.cleanup { it.cancel() }
            }
        }

        provide(BestillingProsessor::class)
        val prosesserBestillingerJob =
            with(CoroutineScope(Dispatchers.IO + MDCContext() + SupervisorJob())) { launch { resolve<BestillingProsessor>().run() } }
        provide("job.prosesserBestillinger") { prosesserBestillingerJob }.cleanup { it.cancel() }
    }

    // Flyttet ned hit, siden vi trenger en DataSource dersom install(MicrometerMetrics) skal inneholde PostgreSQLDatabaseMetrics
    commonConfig()

    // Bør gjøres etter install(MicrometerMetrics) i commonConfig(), for å unngå "A MeterFilter is being configured after a Meter has been
    // registered to this registry"-warnings.
    val metrics: Metrics by dependencies
    DatabaseConfig.dataSource.metricRegistry = metrics.registry

    val rapportService: RapportService by dependencies
    RapportType.entries.forEach { t ->
        metrics.registerGauge(
            "uprosessert_bestilling_${t.name}",
            stateObject = rapportService,
            valueFunction = { it.antallUprosesserteBestillinger(t).toDouble() },
        )
    }

    applicationLifecycleConfig()
    securityConfig()
    routingConfig()
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
