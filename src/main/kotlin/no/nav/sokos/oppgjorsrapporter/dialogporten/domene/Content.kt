package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import kotlinx.serialization.Serializable

@Serializable
data class Content(val title: Value, val summary: Value? = null) {
    companion object {
        fun create(title: String, summary: String?): Content = Content(title.toContentValue(), summary?.toContentValue())
    }

    @Serializable
    data class Value(val value: List<Item>, val mediaType: String = "text/plain") {
        @Serializable data class Item(val value: String, val languageCode: String = "nb")
    }
}

fun String.toContentValue() = Content.Value(value = listOf(Content.Value.Item(this)))
