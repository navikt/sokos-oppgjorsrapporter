package no.nav.sokos.oppgjorsrapporter

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.dependencies
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

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(true)
}

fun Application.module(appConfig: ApplicationConfig = environment.config) {
    val config = resolveConfig(appConfig)
    DatabaseConfig.init(config, isLocal = config.applicationProperties.profile == PropertiesConfig.Profile.LOCAL)

    val applicationState = ApplicationState()
    DatabaseMigrator(DatabaseConfig.adminDataSource, config.postgresProperties.adminRole, applicationState)

    dependencies { provide<DataSource> { DatabaseConfig.dataSource } }

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
