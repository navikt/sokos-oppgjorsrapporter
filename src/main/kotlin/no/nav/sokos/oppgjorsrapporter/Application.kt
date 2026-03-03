package no.nav.sokos.oppgjorsrapporter

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.engine.apache5.Apache5EngineConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import io.ktor.util.AttributeKey
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.serialization.json.Json
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.DefaultAuthClient
import no.nav.sokos.oppgjorsrapporter.auth.NoOpAuthClient
import no.nav.sokos.oppgjorsrapporter.auth.dialogportenTokenGetter
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.DatabaseConfig
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.config.applicationLifecycleConfig
import no.nav.sokos.oppgjorsrapporter.config.commonConfig
import no.nav.sokos.oppgjorsrapporter.config.configFrom
import no.nav.sokos.oppgjorsrapporter.config.createDataSource
import no.nav.sokos.oppgjorsrapporter.config.migrateDatabase
import no.nav.sokos.oppgjorsrapporter.config.routingConfig
import no.nav.sokos.oppgjorsrapporter.config.securityConfig
import no.nav.sokos.oppgjorsrapporter.dialogporten.DialogportenClient
import no.nav.sokos.oppgjorsrapporter.dialogporten.DialogportenHttpClientSetup
import no.nav.sokos.oppgjorsrapporter.ereg.EregHttpClientSetup
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.BestillingMottak
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer
import no.nav.sokos.oppgjorsrapporter.pdp.AltinnPdpService
import no.nav.sokos.oppgjorsrapporter.pdp.LocalhostPdpService
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.BestillingProsessor
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.generator.PdfgenHttpClientSetup
import no.nav.sokos.oppgjorsrapporter.rapport.generator.RapportGenerator
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselProsessor
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselRepository
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService

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

        provide { ApplicationState(disabledBackgroundJobs = emptyList()) }
        val applicationState: ApplicationState by this

        // Vi vil helst ikke registrere denne dataSourcen i DI-registeret, siden den ikke skal brukes til noe annet enn Flyway.  Samtidig er
        // det fint om testene husker å lukke denne dataSourcen også når en testApplication avsluttes; se .cleanup() på
        // DataSource-registreringen under.
        val adminDataSource = createDataSource(config.postgres.adminJdbcUrl)
        migrateDatabase(adminDataSource, applicationState)
        DatabaseConfig.init(config)

        provide { Metrics(PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) }
        provide<DataSource> { DatabaseConfig.dataSource }.cleanup { adminDataSource.close() }
        provide(RapportRepository::class)
        provide(RapportService::class)
        provide(VarselRepository::class)
        provide(VarselService::class)

        // For klasser som trenger en HttpClient (typisk for å snakke med én ekstern tjeneste), velger vi å lage en klient-instans per
        // tjeneste, slik at vi kan justere konfig for JSON-enkoding og -dekoding uavhengig av hva andre tjenester krever i sin
        // input/output.
        provide<EregService> {
            val client =
                httpClient("ereg", EregHttpClientSetup) {
                    install(HttpRequestRetry) {
                        retryOnServerErrors(
                            maxRetries =
                                when (config.application.profile) {
                                    PropertiesConfig.Profile.LOCAL -> 2
                                    else -> 5
                                }
                        )
                        exponentialDelay()
                    }
                }
            EregService(config.restEndpoint.eregBaseUrl, client, resolve())
        }

        provide<RapportGenerator> {
            val client =
                httpClient("pdfgen", PdfgenHttpClientSetup) {
                    install(HttpTimeout) {
                        socketTimeoutMillis = 60_000
                        requestTimeoutMillis = 60_000
                    }
                }
            RapportGenerator(baseUrl = config.restEndpoint.pdfGenBaseUrl, client = client, resolve(), resolve())
        }

        if (config.application.profile == PropertiesConfig.Profile.LOCAL) {
            provide<AuthClient> { NoOpAuthClient() }
            provide<PdpService> { LocalhostPdpService }
        } else {
            provide<AuthClient> { DefaultAuthClient(config.security.tokenEndpoint, config.security.altinn.baseUrl) }
            provide<PdpService> { AltinnPdpService(config.security, resolve(), resolve()) }
        }
        val authClient: AuthClient by this

        provide<DialogportenClient> {
            val client =
                httpClient("dialogporten", DialogportenHttpClientSetup) {
                    install(Auth) {
                        bearer {
                            val tokenGetter = authClient.dialogportenTokenGetter(config.security.altinn.dialogportenScope)
                            loadTokens {
                                logger.info("Laster initielt dialogporten-token")
                                BearerTokens(tokenGetter(), null)
                            }
                            refreshTokens {
                                logger.info("Refresher dialogporten-token")
                                BearerTokens(tokenGetter(), null)
                            }
                        }
                    }
                }
            DialogportenClient(config.security.altinn.baseUrl, client)
        }

        val consumerKeys =
            if (config.mq.enabled) {
                config.mq.queues.map { inQueue ->
                    val consumerKey = "mq.consumer.${inQueue.key}"
                    provide(consumerKey) { MqConsumer(config.mq, inQueue.rapportType, inQueue.queueName) }
                    consumerKey
                }
            } else {
                emptyList()
            }

        if (config.application.disableBackgroundJobs) {
            applicationState.disabledBackgroundJobs += BestillingMottak::class
        }
        provide {
            val consumers: List<MqConsumer> = consumerKeys.map { resolve<MqConsumer>(it) }
            BestillingMottak(consumers, resolve(), resolve(), resolve(), resolve(), resolve())
        }

        if (consumerKeys.isNotEmpty()) {
            val mqErrors = mutableListOf<String>()
            applicationState.registerSystem("MQ") { mqErrors }

            val exceptionHandler = CoroutineExceptionHandler { _, e ->
                logger.error(TEAM_LOGS_MARKER, e) { "Mottatt alvorlig exception i MQ-subsystemet" }
                // Ved å legge til en feil på et registrert system, flippes applicationState.ready til `false`
                mqErrors.add(e.toString())
                applicationState.alive = false
            }

            provideJob<BestillingMottak>(
                with(CoroutineScope(Dispatchers.IO + exceptionHandler + MDCContext() + SupervisorJob())) {
                    launch { resolve<BestillingMottak>().run() }
                }
            )
        }

        if (config.application.disableBackgroundJobs) {
            applicationState.disabledBackgroundJobs += BestillingProsessor::class
        }
        provide(BestillingProsessor::class)
        provideJob<BestillingProsessor>(
            with(CoroutineScope(Dispatchers.IO + MDCContext() + SupervisorJob())) { launch { resolve<BestillingProsessor>().run() } }
        )

        if (config.application.disableBackgroundJobs) {
            applicationState.disabledBackgroundJobs += VarselProsessor::class
        }
        provide(VarselProsessor::class)
        provideJob<VarselProsessor>(
            with(CoroutineScope(Dispatchers.IO + MDCContext() + SupervisorJob())) { launch { resolve<VarselProsessor>().run() } }
        )
    }

    // Flyttet ned hit, siden vi trenger en DataSource dersom install(MicrometerMetrics) skal inneholde PostgreSQLDatabaseMetrics
    commonConfig()

    // Bør gjøres etter install(MicrometerMetrics) i commonConfig(), for å unngå "A MeterFilter is being configured after a Meter has been
    // registered to this registry"-warnings.
    val metrics: Metrics by dependencies
    DatabaseConfig.dataSource.metricRegistry = metrics.registry

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

private fun httpClient(
    loggerName: String,
    setupObject: HttpClientSetup,
    block: HttpClientConfig<Apache5EngineConfig>.() -> Unit = {},
): HttpClient {
    val httpLogger = KotlinLogging.logger("http-client.$loggerName")
    val client =
        HttpClient(Apache5) {
            expectSuccess = true
            install(ContentNegotiation) { json(setupObject.jsonConfig) }
            install(Logging) {
                logger =
                    object : Logger {
                        override fun log(message: String) {
                            httpLogger.debug(TEAM_LOGS_MARKER, message)
                        }
                    }
                level = LogLevel.ALL
                sanitizeHeader { false }
            }

            block()
        }

    client.plugin(HttpSend).intercept { request ->
        try {
            execute(request)
        } catch (e: Exception) {
            httpLogger.error(TEAM_LOGS_MARKER, e) { "Feil ved kall mot $loggerName" }
            throw e
        }
    }

    return client
}

interface HttpClientSetup {
    val jsonConfig: Json
}

abstract class BakgrunnsJobb(private val applicationState: ApplicationState) {
    private val logger: KLogger = KotlinLogging.logger(javaClass.canonicalName)

    abstract suspend fun run()

    suspend fun whenEnabled(block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        if (applicationState.disabledBackgroundJobs.contains(this::class)) {
            logger.trace { "${javaClass.simpleName}.run() disablet" }
            delay(1.seconds)
        } else {
            block()
        }
    }
}

private inline fun <reified T : BakgrunnsJobb> DependencyRegistry.provideJob(job: Job) {
    val jobName = "job.${T::class.simpleName}"
    provide(jobName) { job }
        .cleanup {
            // Merk at .cancel() ikke er nok her, da det bare vil sende et signal om at jobben skal kanselleres.
            // Vi vil at cleanup-prosessen skal vente til jobben (og dermed alle barne-jobber den evt. har spawnet) er ferdig
            // kansellert.
            runBlocking { it.cancelAndJoin() }
        }
    // Sikre at jobName-dependencyen faktisk har blitt resolvet minst en gang, slik at cleanup ikke vil bli skippet
    runBlocking { resolve<Job>(jobName) }
}
