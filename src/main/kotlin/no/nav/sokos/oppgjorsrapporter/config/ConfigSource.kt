package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import java.io.File
import java.util.Properties
import kotlin.collections.component1
import kotlin.collections.component2

interface ConfigSource {
    fun get(key: String): String
}

fun configSourceFrom(config: ApplicationConfig): ConfigSource {
    val clusterName = System.getenv("NAIS_CLUSTER_NAME") ?: System.getProperty("NAIS_CLUSTER_NAME")
    return when (clusterName) {
        "dev-gcp",
        "prod-gcp" -> FallbackConfigSource(config)
        else -> LocalDevConfigSource(config)
    }
}

// This is the default config fallback if nothing else is set (application.conf)
private val baseConfig = ApplicationConfig("application.conf")

// Loads defaults.properties for local dev
private val fallbackOverrides =
    MapApplicationConfig().apply {
        val file = File("defaults.properties")
        if (file.exists()) {
            val props = Properties().apply { load(file.inputStream()) }
            props.forEach { (k, v) -> put(k.toString(), v.toString()) }
        }
    }

private class FallbackConfigSource(private val config: ApplicationConfig) : ConfigSource {
    override fun get(key: String): String =
        System.getenv(key)
            ?: System.getProperty(key)
            ?: config.propertyOrNull(key)?.getString()
            ?: fallbackOverrides.propertyOrNull(key)?.getString()
            ?: baseConfig.propertyOrNull(key)?.getString()
            ?: throw IllegalStateException("Missing configuration key: $key")
}

private class LocalDevConfigSource(config: ApplicationConfig) : ConfigSource {
    private val fallback = FallbackConfigSource(config)

    override fun get(key: String): String = fallback.get(key)
}

class MapOverridingConfigSource(private val overrides: Map<String, String>, private val fallback: ConfigSource) : ConfigSource {
    override fun get(key: String): String = overrides[key] ?: fallback.get(key)
}
