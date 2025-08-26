package no.nav.sokos.oppgjorsrapporter

import io.ktor.server.config.MapApplicationConfig
import org.testcontainers.containers.PostgreSQLContainer

internal const val API_BASE_PATH = "/api/v1"

object TestUtil {
    // Her ligger alt verktøy metoder som brukes i testene

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
}
