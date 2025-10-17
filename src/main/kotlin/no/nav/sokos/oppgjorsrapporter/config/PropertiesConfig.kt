package no.nav.sokos.oppgjorsrapporter.config

import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ApplicationConfigValue
import java.io.File
import java.net.URI

fun configFrom(config: ApplicationConfig): PropertiesConfig.Configuration {
    val configSource = configSourceFrom(config)
    return PropertiesConfig.Configuration(configSource)
}

class CompositeApplicationConfig(private val primary: ApplicationConfig, private vararg val fallbacks: ApplicationConfig) :
    ApplicationConfig {
    override fun property(path: String): ApplicationConfigValue = propertyOrNull(path) ?: primary.property(path)

    override fun propertyOrNull(path: String): ApplicationConfigValue? =
        primary.propertyOrNull(path) ?: fallbacks.firstNotNullOfOrNull { it.propertyOrNull(path) }

    override fun config(path: String): ApplicationConfig =
        CompositeApplicationConfig(primary.config(path), *fallbacks.map { it.config(path) }.toTypedArray())

    override fun configList(path: String): List<ApplicationConfig> =
        primary.configList(path).ifEmpty { fallbacks.firstNotNullOf { it.configList(path).ifEmpty { null } } }

    override fun keys(): Set<String> = primary.keys() + fallbacks.flatMap { it.keys() }

    override fun toMap(): Map<String, Any> =
        (listOf(primary) + fallbacks).foldRight(emptyMap()) { fallback, config -> config + fallback.toMap().mapValues { it.value as Any } }
}

object PropertiesConfig {
    private val defaultProperties = ConfigurationMap(mapOf("NAIS_APP_NAME" to "sokos-oppgjorsrapporter", "NAIS_NAMESPACE" to "okonomi"))

    private val localDevProperties = ConfigurationMap(mapOf("APPLICATION_PROFILE" to Profile.LOCAL.toString()))

    private val devProperties = ConfigurationMap(mapOf("APPLICATION_PROFILE" to Profile.DEV.toString()))
    private val prodProperties = ConfigurationMap(mapOf("APPLICATION_PROFILE" to Profile.PROD.toString()))

    private val config =
        when (System.getenv("NAIS_CLUSTER_NAME") ?: System.getProperty("NAIS_CLUSTER_NAME")) {
            "dev-gcp" ->
                ConfigurationProperties.systemProperties() overriding
                    EnvironmentVariables() overriding
                    devProperties overriding
                    defaultProperties
            "prod-gcp" ->
                ConfigurationProperties.systemProperties() overriding
                    EnvironmentVariables() overriding
                    prodProperties overriding
                    defaultProperties
            else ->
                ConfigurationProperties.systemProperties() overriding
                    EnvironmentVariables() overriding
                    ConfigurationProperties.fromOptionalFile(File("defaults.properties")) overriding
                    localDevProperties overriding
                    defaultProperties
        }

    operator fun get(key: String): String = config[Key(key, stringType)]

    fun getOrEmpty(key: String): String = config.getOrElse(Key(key, stringType), "")

    data class Configuration(
        val applicationProperties: ApplicationProperties,
        val securityProperties: SecurityProperties,
        val postgresProperties: PostgresProperties,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            applicationProperties = ApplicationProperties(source),
            securityProperties = SecurityProperties(source),
            postgresProperties = PostgresProperties(source),
        )
    }

    data class ApplicationProperties(
        val naisAppName: String = get("NAIS_APP_NAME"),
        val profile: Profile = Profile.valueOf(get("APPLICATION_PROFILE")),
    ) {
        constructor(
            source: ConfigSource
        ) : this(naisAppName = source.get("APP_NAME"), profile = Profile.valueOf(source.get("APPLICATION_PROFILE")))
    }

    data class PostgresProperties(val adminJdbcUrl: String, val queryJdbcUrl: String) {
        constructor(
            source: ConfigSource
        ) : this(
            adminJdbcUrl = source.get("${naisPrefix}_${defaultUser}_${dbName}_JDBC_URL"),
            queryJdbcUrl = source.get("${naisPrefix}_APPBRUKER_${dbName}_JDBC_URL"),
        )

        companion object {
            private val naisPrefix = "NAIS_DATABASE"
            private val defaultUser = "SOKOS_OPPGJORSRAPPORTER"
            private val dbName = "SOKOS_OPPGJORSRAPPORTER_DB"
        }
    }

    data class SecurityProperties(val azureAdProperties: AzureAdProperties, val maskinportenProperties: MaskinportenProperties) {
        constructor(
            source: ConfigSource
        ) : this(azureAdProperties = AzureAdProperties(source), maskinportenProperties = MaskinportenProperties(source))
    }

    class AzureAdProperties(val clientId: String, val wellKnownUrl: String) {
        constructor(
            source: ConfigSource
        ) : this(clientId = source.get("AZURE_APP_CLIENT_ID"), wellKnownUrl = source.get("AZURE_APP_WELL_KNOWN_URL"))
    }

    class MaskinportenProperties(
        val wellKnownUrl: String,
        val eksponertScope: String,
        val pdpScope: String,
        val altinn3BaseUrl: URI,
        val subscriptionKey: String,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            wellKnownUrl = source.get("maskinporten.wellKnownUrl"),
            eksponertScope = source.get("maskinporten.eksponert_scope"),
            pdpScope = source.get("maskinporten.pdp_scope"),
            altinn3BaseUrl = URI.create(source.get("maskinporten.altinn_base_url")),
            subscriptionKey = source.get("ALTINN_SUBSCRIPTION_KEY"),
        )
    }

    enum class Profile {
        LOCAL,
        DEV,
        PROD,
    }
}
