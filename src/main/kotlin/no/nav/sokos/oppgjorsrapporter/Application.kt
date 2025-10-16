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
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.DatabaseConfig
import no.nav.sokos.oppgjorsrapporter.config.DatabaseMigrator
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.applicationLifecycleConfig
import no.nav.sokos.oppgjorsrapporter.config.commonConfig
import no.nav.sokos.oppgjorsrapporter.config.configFrom
import no.nav.sokos.oppgjorsrapporter.config.createDataSource
import no.nav.sokos.oppgjorsrapporter.config.routingConfig
import no.nav.sokos.oppgjorsrapporter.config.securityConfig
import no.nav.sokos.oppgjorsrapporter.pdp.AltinnPdpService
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module)
        .also { it.addShutdownHook { it.stop(shutdownGracePeriod = 3, shutdownTimeout = 5, timeUnit = TimeUnit.SECONDS) } }
        .start(true)
}

fun Application.module(appConfig: ApplicationConfig = environment.config) {
    val config = resolveConfig(appConfig)

    val applicationState = ApplicationState()
    DatabaseMigrator(createDataSource(config.postgresProperties.adminJdbcUrl), applicationState)

    DatabaseConfig.init(config)

    install(Resources)

    dependencies {
        provide<DataSource> { DatabaseConfig.dataSource }
        provide(RapportService::class)
        provide<PdpService>(AltinnPdpService::class)
    }

    commonConfig()
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
