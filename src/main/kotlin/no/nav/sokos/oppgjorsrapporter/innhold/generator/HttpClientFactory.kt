package no.nav.sokos.oppgjorsrapporter.innhold.generator

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig

class HttpClientFactory {

    internal fun createHttpClient(): HttpClient = HttpClient(Apache5) { configure() }

    internal fun HttpClientConfig<*>.configure() {
        expectSuccess = true

        install(ContentNegotiation) { json(commonJsonConfig) }
    }
}
