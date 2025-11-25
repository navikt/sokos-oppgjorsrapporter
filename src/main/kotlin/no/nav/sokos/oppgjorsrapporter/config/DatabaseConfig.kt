package no.nav.sokos.oppgjorsrapporter.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object DatabaseConfig {
    private lateinit var dataSourcePriv: HikariDataSource
    val dataSource: HikariDataSource by this::dataSourcePriv

    // Skal kun overstyres fra tester; nødvendig for å opprette "appbruker"-rollen som ved deploy lages av Nais
    var migrationInitSql: String? = null

    fun init(config: PropertiesConfig.Configuration) {
        dataSourcePriv = createDataSource(config.postgresProperties.queryJdbcUrl)
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
