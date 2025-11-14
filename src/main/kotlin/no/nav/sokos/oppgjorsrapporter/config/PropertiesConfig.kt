package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ApplicationConfigValue
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

    data class ApplicationProperties(val naisAppName: String, val profile: Profile) {
        constructor(
            source: ConfigSource
        ) : this(naisAppName = source.get("APP_NAME"), profile = Profile.valueOf(source.get("APPLICATION_PROFILE")))
    }

    data class PostgresProperties(val adminJdbcUrl: String, val queryJdbcUrl: String, val databaseName: String) {
        constructor(
            source: ConfigSource
        ) : this(
            adminJdbcUrl = source.get("db.admin_jdbc_url"),
            queryJdbcUrl = source.get("db.query_jdbc_url"),
            databaseName = source.get("db.name"),
        )
    }

    data class MqProperties(
        val enabled: Boolean,
        val host: String,
        val port: Int,
        val managerName: String,
        val channel: String,
        val queues: List<MqQueueProperties>,
        val username: String,
        val password: String,
    ) {
        constructor(
            source: ConfigSource
        ) : this(
            enabled = source.get("mq.enabled").toBoolean(),
            host = source.get("mq.host"),
            port = source.get("mq.port").toInt(),
            managerName = source.get("mq.managerName"),
            channel = source.get("mq.channel"),
            queues = listOf("refusjon").map { MqQueueProperties(source, it) },
            username = source.get("mq.username"),
            password = source.get("mq.password"),
        )
    }

    data class MqQueueProperties(val key: String, val queueName: String) {
        constructor(source: ConfigSource, key: String) : this(key, source.get("mq.$key.queueName"))
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
