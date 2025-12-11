package no.nav.sokos.oppgjorsrapporter.innhold.generator

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ApiError(val status: HttpStatusCode, val errorMessage: String, val url: Url) {
    constructor(
        response: HttpResponse,
        errorMessage: String,
    ) : this(status = response.status, errorMessage = errorMessage, url = response.request.url)
}

suspend fun HttpResponse.eregErrorMessage() = body<JsonElement>().jsonObject["melding"]?.jsonPrimitive?.content
