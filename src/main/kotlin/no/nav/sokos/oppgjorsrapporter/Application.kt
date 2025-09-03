package no.nav.sokos.oppgjorsrapporter

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.resources.Resources
import io.ktor.util.AttributeKey
import javax.sql.DataSource
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.DatabaseConfig
import no.nav.sokos.oppgjorsrapporter.config.DatabaseMigrator
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.applicationLifecycleConfig
import no.nav.sokos.oppgjorsrapporter.config.commonConfig
import no.nav.sokos.oppgjorsrapporter.config.configFrom
import no.nav.sokos.oppgjorsrapporter.config.routingConfig
import no.nav.sokos.oppgjorsrapporter.config.securityConfig
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(true)
}

fun Application.module(appConfig: ApplicationConfig = environment.config) {
    val config = resolveConfig(appConfig)
    DatabaseConfig.init(config, isLocal = config.applicationProperties.profile == PropertiesConfig.Profile.LOCAL)

    val applicationState = ApplicationState()
    DatabaseMigrator(DatabaseConfig.adminDataSource, config.postgresProperties.adminRole, applicationState)

    install(DI) {
        onShutdown = { dependencyKey, instance ->
            when (instance) {
                // Vi ønsker bare en DataSource i bruk for en hel test-kjøring, selv om flere tester start/stopper applikasjonen;
                // dette er en opt-out av auto-close-greiene til Kotlins DI-extension:
                is DataSource -> {}
                is AutoCloseable -> instance.close()
            }
        }
    }
    install(Resources)

    dependencies {
        provide<DataSource> { DatabaseConfig.dataSource }
        provide(RapportService::class)
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
