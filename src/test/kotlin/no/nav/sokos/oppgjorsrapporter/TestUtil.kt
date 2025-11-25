package no.nav.sokos.oppgjorsrapporter

import com.ibm.mq.testcontainers.MQContainer
import io.ktor.server.application.Application
import io.ktor.server.config.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import java.sql.Connection.TRANSACTION_SERIALIZABLE
import java.sql.DatabaseMetaData
import javax.sql.DataSource
import kotlinx.coroutines.test.TestResult
import mu.KotlinLogging
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestUtil.testApplicationConfig
import no.nav.sokos.oppgjorsrapporter.config.CompositeApplicationConfig
import no.nav.sokos.oppgjorsrapporter.config.DatabaseConfig
import org.testcontainers.containers.PostgreSQLContainer

private val logger = KotlinLogging.logger {}

object TestUtil {
    fun withFullApplication(
        dbContainer: PostgreSQLContainer<Nothing>,
        mqContainer: MQContainer? = null,
        dependencyOverrides: Application.() -> Unit = {},
        thunk: suspend ApplicationTestBuilder.() -> Unit,
    ): TestResult = withMockOAuth2Server {
        withTestApplication(dbContainer = dbContainer, mqContainer = mqContainer, dependencyOverrides = dependencyOverrides, thunk = thunk)
    }

    fun testApplicationConfig(
        dbContainer: PostgreSQLContainer<Nothing>,
        mqContainer: MQContainer?,
        server: MockOAuth2Server,
    ): CompositeApplicationConfig =
        CompositeApplicationConfig(
            testApplicationProfile(),
            testcontainerDbOverrides(dbContainer),
            testContainerMqOverrides(mqContainer),
            server.authConfigOverrides(),
            ApplicationConfig("application.conf"),
        )

    fun testApplicationProfile() = MapApplicationConfig().apply { put("APPLICATION_PROFILE", "LOCAL") }

    fun testContainerMqOverrides(container: MQContainer?) =
        MapApplicationConfig().apply {
            val enabled = container != null
            put("mq.enabled", enabled.toString())
            if (enabled) {
                put("mq.host", container.host)
                put("mq.port", container.port.toString())
                put("mq.managerName", container.queueManager)
                put("mq.channel", container.channel)
                put("mq.username", container.appUser)
                put("mq.password", container.appPassword)
            }
        }

    fun testcontainerDbOverrides(container: PostgreSQLContainer<Nothing>): MapApplicationConfig =
        MapApplicationConfig()
            .apply {
                val url = container.jdbcUrl
                val prefix = if (url.contains('?')) '&' else '?'
                val adminJdbcUrl = "${url}${prefix}user=${container.username}&password=${container.password}"
                put("db.admin_jdbc_url", adminJdbcUrl)
                val queryJdbcUrl = "${url}${prefix}user=appbruker&password=test"
                put("db.query_jdbc_url", queryJdbcUrl)
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

    fun <T> withEmptyDatabase(dataSource: DataSource, block: () -> T): T {
        deleteAllTables(dataSource) // Vi vil alltid helst starte med en kjent databasetilstand.
        val res = block()
        updateAutoincrementSequences(dataSource)
        return res
    }

    fun loadDataSet(fileToLoad: String, dataSource: DataSource) =
        withEmptyDatabase(dataSource) {
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

fun MockOAuth2Server.authConfigOverrides() =
    MapApplicationConfig().apply {
        put("AZURE_APP_CLIENT_ID", "default")
        put("AZURE_APP_WELL_KNOWN_URL", wellKnownUrl("default").toString())
        put("maskinporten.wellKnownUrl", wellKnownUrl("maskinporten").toString())
    }

fun MockOAuth2Server.withTestApplication(
    dbContainer: PostgreSQLContainer<Nothing>,
    mqContainer: MQContainer? = null,
    dependencyOverrides: Application.() -> Unit = {},
    thunk: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        configureTestApplicationEnvironment(dbContainer = dbContainer, mqContainer = mqContainer, server = this@withTestApplication)

        application {
            dependencyOverrides()
            module()
        }
        startApplication()

        thunk()
    }
}

fun TestApplicationBuilder.configureTestApplicationEnvironment(
    dbContainer: PostgreSQLContainer<Nothing>,
    mqContainer: MQContainer? = null,
    server: MockOAuth2Server,
) {
    environment { config = testApplicationConfig(dbContainer, mqContainer, server) }
}
