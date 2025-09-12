package no.nav.sokos.oppgjorsrapporter.config

import javax.sql.DataSource
import mu.KotlinLogging
import org.flywaydb.core.api.output.MigrateOutput

private val logger = KotlinLogging.logger {}

class DatabaseMigrator(dataSource: DataSource, applicationState: ApplicationState) {
    var errors: MutableList<String> = mutableListOf()

    init {
        applicationState.registerSystem("Flyway") { errors.toList() }

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
        failed.filterNotNull().forEach { migration: MigrateOutput ->
            errors.addLast("Failed migration: " + migration.filepath + " " + migration.description)
        }
        logger.info { "Migration finished" }
    }
}
