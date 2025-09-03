package no.nav.sokos.oppgjorsrapporter

import io.ktor.server.config.MapApplicationConfig
import java.sql.Connection.TRANSACTION_SERIALIZABLE
import java.sql.DatabaseMetaData
import javax.sql.DataSource
import kotlin.use
import mu.KotlinLogging
import org.testcontainers.containers.PostgreSQLContainer

private val logger = KotlinLogging.logger {}

object TestUtil {
    fun getOverrides(container: PostgreSQLContainer<Nothing>): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("POSTGRES_USER_USERNAME", container.username)
            put("POSTGRES_USER_PASSWORD", container.password)
            put("POSTGRES_ADMIN_USERNAME", container.username)
            put("POSTGRES_ADMIN_PASSWORD", container.password)
            put("POSTGRES_NAME", container.databaseName)
            put("POSTGRES_PORT", container.firstMappedPort.toString())
            put("POSTGRES_HOST", container.host)
            put("APPLICATION_PROFILE", "LOCAL")
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
