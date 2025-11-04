package no.nav.sokos.oppgjorsrapporter.serialization

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive

abstract class AsStringSerializer<T : Any>(serialName: String, private val parse: (String) -> T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        value.toString().let(encoder::encodeString)
    }

    override fun deserialize(decoder: Decoder): T = decoder.decodeString().runCatching(parse).getOrElse { throw SerializationException(it) }
}

object LocalDateAsStringSerializer :
    AsStringSerializer<LocalDate>(serialName = "utbetaling.pengeflyt.kotlinx.LocalDateAsStringSerializer", parse = LocalDate::parse)

object InstantAsStringSerializer :
    AsStringSerializer<Instant>(serialName = "utbetaling.pengeflyt.kotlinx.LocalDateAsStringSerializer", parse = Instant::parse)

object BigDecimalSerializer : KSerializer<BigDecimal> {

    override val descriptor = PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.DOUBLE)

    /** If decoding JSON uses [JsonDecoder.decodeJsonElement] to get the raw content, otherwise decodes using [Decoder.decodeString]. */
    override fun deserialize(decoder: Decoder): BigDecimal =
        when (decoder) {
            is JsonDecoder -> decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal()
            else -> decoder.decodeString().toBigDecimal()
        }

    /**
     * If encoding JSON uses [JsonUnquotedLiteral] to encode the exact [BigDecimal] value.
     *
     * Otherwise, [value] is encoded using encodes using [Encoder.encodeString].
     */
    @ExperimentalSerializationApi
    override fun serialize(encoder: Encoder, value: BigDecimal) =
        when (encoder) {
            is JsonEncoder -> encoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
            else -> encoder.encodeString(value.toPlainString())
        }
}
