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

    data class Configuration(
        val applicationProperties: ApplicationProperties,
        val securityProperties: SecurityProperties,
        val postgresProperties: PostgresProperties,
        val mqConfiguration: MqProperties,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            applicationProperties = ApplicationProperties(source),
            securityProperties = SecurityProperties(source),
            postgresProperties = PostgresProperties(source),
            mqConfiguration = MqProperties(source),
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

    data class MqProperties(
        val enabled: Boolean,
        val mqHost: String,
        val mqPort: Int,
        val mqManagerName: String,
        val mqQueueName: String,
        val mqChannel: String,
        val mqUrBackoutQueue: String,
        val mqUsername: String,
        val mqPassword: String,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            enabled = source.get("mq.enabled").toBoolean(),
            mqHost = source.get("mq.host"),
            mqPort = source.get("mq.port").toInt(),
            mqManagerName = source.get("mq.managerName"),
            mqQueueName = source.get("mq.name"),
            mqChannel = source.get("mq.channel"),
            mqUrBackoutQueue = source.get("mq.fraUrBackoutQueue"),
            mqUsername = source.get("mq.username"),
            mqPassword = source.get("mq.password"),
        )
    }

    data class SecurityProperties(
        val azureAdProperties: AzureAdProperties,
        val maskinportenProperties: MaskinportenProperties,
        val tokenEndpoint: String,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            azureAdProperties = AzureAdProperties(source),
            maskinportenProperties = MaskinportenProperties(source),
            source.get("authclient.tokenEndpoint"),
        )
    }

    class AzureAdProperties(val clientId: String, val wellKnownUrl: String) {
        constructor(
            source: ConfigSource
        ) : this(clientId = source.get("AZURE_APP_CLIENT_ID"), wellKnownUrl = source.get("AZURE_APP_WELL_KNOWN_URL"))
    }

    class MaskinportenProperties(
        val wellKnownUrl: String,
        val eksponertScope: String,
        val altinn3BaseUrl: URI,
        val subscriptionKey: String,
        val pdpScope: String,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            wellKnownUrl = source.get("maskinporten.wellKnownUrl"),
            eksponertScope = source.get("maskinporten.eksponert_scope"),
            altinn3BaseUrl = URI.create(source.get("altinn.base_url")),
            subscriptionKey = source.get("altinn.subscription_key"),
            pdpScope = source.get("altinn.pdp_scope"),
        )
    }

    enum class Profile {
        LOCAL,
        DEV,
        PROD,
    }
}
