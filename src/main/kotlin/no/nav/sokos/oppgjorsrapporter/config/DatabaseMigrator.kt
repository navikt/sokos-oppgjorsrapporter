package no.nav.sokos.oppgjorsrapporter.config

import javax.sql.DataSource
import mu.KotlinLogging
import org.flywaydb.core.api.output.MigrateOutput

private val logger = KotlinLogging.logger {}

fun migrateDatabase(dataSource: DataSource, applicationState: ApplicationState) {
    val migrationsResult =
        org.flywaydb.core.Flyway.configure()
            .initSql(DatabaseConfig.migrationInitSql)
            .dataSource(dataSource)
            .lockRetryCount(-1)
            .validateMigrationNaming(true)
            .sqlMigrationSeparator("__")
            .sqlMigrationPrefix("V")
            .load()
            .migrate()
    val failed: List<MigrateOutput?> = migrationsResult.failedMigrations ?: emptyList()
    applicationState.registerSystem("Flyway") { failed.filterNotNull().map { "Failed migration: " + it.filepath + " " + it.description } }
    logger.info { "Migration finished" }
}
