package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import kotlinx.serialization.Serializable

@Serializable
data class ApiAction(val name: String, val endpoints: List<Endpoint>? = null, val action: Action) {
    @Serializable data class Endpoint(val url: String, val httpMethod: HttpMethod, val documentationUrl: String)

    @Serializable
    enum class HttpMethod {
        GET,
        POST,
        PUT,
        DELETE,
        PATCH,
    }
}

enum class Action {
    access
}

@Serializable
data class GuiAction(val title: List<Content.Value.Item>, val url: String, val priority: Priority, val action: Action) {
    enum class Priority {
        Primary,
        Secondary,
        Tertiary,
    }
}
