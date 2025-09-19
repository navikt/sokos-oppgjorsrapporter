package no.nav.sokos.oppgjorsrapporter.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object DatabaseConfig {
    private lateinit var applicationProperties: PropertiesConfig.ApplicationProperties
    private lateinit var postgresProperties: PropertiesConfig.PostgresProperties
    private lateinit var dataSourcePriv: HikariDataSource

    val dataSource: HikariDataSource by lazy { dataSourcePriv }

    // Skal kun overstyres fra tester; nødvendig for å opprette "appbruker"-rollen som ved deploy lages av Nais
    var migrationInitSql: String? = null

    fun init(config: PropertiesConfig.Configuration) {
        this.applicationProperties = config.applicationProperties
        this.postgresProperties = config.postgresProperties
        check(::postgresProperties.isInitialized) { "PostgresProperties not initialized" }
        check(::applicationProperties.isInitialized) { "ApplicationProperties not initialized" }

        dataSourcePriv = createDataSource(postgresProperties.queryJdbcUrl)
    }
}

fun createDataSource(url: String): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            jdbcUrl = url
        }
    )
