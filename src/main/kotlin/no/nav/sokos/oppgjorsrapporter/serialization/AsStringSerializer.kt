package no.nav.sokos.oppgjorsrapporter.serialization

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

abstract class AsStringSerializer<T : Any>(serialName: String, private val parse: (String) -> T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        value.toString().let(encoder::encodeString)
    }

    override fun deserialize(decoder: Decoder): T = decoder.decodeString().runCatching(parse).getOrElse { throw SerializationException(it) }
}

@OptIn(ExperimentalSerializationApi::class)
abstract class AsNullableStringSerializer<T : Any>(
    private val delegateTo: KSerializer<T>,
    private val treatAsNull: (String) -> Boolean = { it.isBlank() },
) : KSerializer<T?> {
    override val descriptor =
        PrimitiveSerialDescriptor(delegateTo.descriptor.serialName + ".nullable", delegateTo.descriptor.kind as PrimitiveKind).nullable

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else delegateTo.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T? {
        if (decoder.decodeNotNullMark()) {
            val str = decoder.decodeString()
            return if (treatAsNull(str)) null else delegateTo.deserialize(decoder)
        } else {
            return decoder.decodeNull()
        }
    }
}

object LocalDateAsStringSerializer :
    AsStringSerializer<LocalDate>(serialName = "utbetaling.pengeflyt.kotlinx.LocalDateAsStringSerializer", parse = LocalDate::parse)

object LocalDateAsNullableStringSerializer : AsNullableStringSerializer<LocalDate>(LocalDateAsStringSerializer)

object InstantAsStringSerializer :
    AsStringSerializer<Instant>(serialName = "utbetaling.pengeflyt.kotlinx.LocalDateAsStringSerializer", parse = Instant::parse)
