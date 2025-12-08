package no.nav.sokos.oppgjorsrapporter.innhold.generator

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ApiError(val timestamp: Instant, val status: Int, val error: String, val message: String?, val path: String)

suspend fun HttpResponse.errorMessage() = body<JsonElement>().jsonObject["melding"]?.jsonPrimitive?.content
