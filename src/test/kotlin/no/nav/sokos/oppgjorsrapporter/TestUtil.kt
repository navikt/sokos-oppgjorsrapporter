package no.nav.sokos.oppgjorsrapporter

import io.ktor.server.config.*
import java.sql.Connection.TRANSACTION_SERIALIZABLE
import java.sql.DatabaseMetaData
import javax.sql.DataSource
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.DatabaseConfig
import org.testcontainers.containers.PostgreSQLContainer

private val logger = KotlinLogging.logger {}

object TestUtil {
    fun getOverrides(container: PostgreSQLContainer<Nothing>): MapApplicationConfig =
        MapApplicationConfig()
            .apply {
                val url = container.jdbcUrl
                val prefix = if (url.contains('?')) '&' else '?'
                val jdbcUrl = "${url}${prefix}user=${container.username}&password=${container.password}"
                put("NAIS_DATABASE_SOKOS_OPPGJORSRAPPORTER_SOKOS_OPPGJORSRAPPORTER_DB_JDBC_URL", jdbcUrl)
                put("NAIS_DATABASE_SOKOS_OPPGJORSRAPPORTER_SOKOS_OPPGJORSRAPPORTER_DB_APPBRUKER_JDBC_URL", jdbcUrl)
                put("APPLICATION_PROFILE", "LOCAL")
            }
            .also {
                // Sørg for at lokal database har en egen 'appbruker'-bruker på samme måte som spesifisert i Nais-specen - slik at
                // Flyway-migreringene kan gi rettigheter til denne brukeren
                DatabaseConfig.migrationInitSql =
                    $$"""
                        DO
                        $do$
                        BEGIN
                           IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE  rolname = 'appbruker') THEN
                              RAISE NOTICE 'Role "appbruker" already exists. Skipping.';
                           ELSE
                              CREATE ROLE appbruker LOGIN PASSWORD 'test';
                           END IF;
                        END
                        $do$;
                    """
                        .trimIndent()
            }

    fun readFile(fileName: String): String =
        this::class.java.classLoader.getResourceAsStream(fileName)?.bufferedReader()?.readLines()?.joinToString(separator = "\n")!!

    fun loadDataSet(fileToLoad: String, dataSource: DataSource) {
        deleteAllTables(dataSource) // Vi vil alltid helst starte med en kjent databasetilstand.

        val sql = readFile(fileToLoad)
        val connection = dataSource.connection
        try {
            connection.transactionIsolation = TRANSACTION_SERIALIZABLE
            connection.autoCommit = false
            logger.debug { "Loading data set from $fileToLoad" }
            connection.prepareStatement(sql).execute()
            connection.commit()
        } finally {
            connection.close()
        }

        updateAutoincrementSequences(dataSource)
    }

    fun deleteAllTables(dataSource: DataSource) =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = TRANSACTION_SERIALIZABLE

            val tables = tableNames(connection.metaData).joinToString { "${it.schemaName}.${it.tableName}" }
            logger.debug { "Deleting data from tables: $tables" }

            connection.prepareStatement("SET CONSTRAINTS ALL DEFERRED").execute()
            connection.prepareStatement("TRUNCATE $tables").execute()
            connection.commit()
        }

    data class Table(val schemaName: String, val tableName: String)

    private fun tableNames(metadata: DatabaseMetaData): List<Table> =
        metadata.getTables(null, null, null, arrayOf("TABLE")).use { resultSet ->
            val results = mutableListOf<Table>()
            while (resultSet.next()) {
                val schema = resultSet.getString("TABLE_SCHEM") // Med takk til Sun for ubrukelig tabellnavn
                val tableName = resultSet.getString("TABLE_NAME")
                if (tableName.uppercase() != "FLYWAY_SCHEMA_HISTORY") {
                    results.add(Table(schema, tableName))
                }
            }
            results
        }

    fun updateAutoincrementSequences(dataSource: DataSource) =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = TRANSACTION_SERIALIZABLE

            tableNames(connection.metaData).forEach { table ->
                val autoIncrementColumns = mutableListOf<String>()
                connection.metaData.getColumns(null, table.schemaName, table.tableName, null).use { resultSet ->
                    while (resultSet.next()) {
                        if (resultSet.getString("IS_AUTOINCREMENT") == "YES") {
                            autoIncrementColumns.add(resultSet.getString("COLUMN_NAME"))
                        }
                    }
                }
                val tableName = "${table.schemaName}.${table.tableName}"
                logger.debug { "Resetting autoincrement sequences for $tableName columns: ${autoIncrementColumns.joinToString()}}" }
                autoIncrementColumns.forEach { column ->
                    connection
                        .prepareStatement(
                            """
                                SELECT setval(
                                    pg_get_serial_sequence('$tableName', '$column'),
                                    (SELECT MAX($column) FROM $tableName),
                                    true
                                );
                            """
                                .trimIndent()
                        )
                        .execute()
                }
            }
        }
}
