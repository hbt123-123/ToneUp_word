package top.toneup.word.data.gateway

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Accepts both `/en/{word}` shapes: a JSON array (normal dictionaryapi answer) or a bare JSON
 * object (the gateway's `ecdict-fallback` degradation). Anything else decodes to an empty list,
 * which the cascade treats as "miss" and passes on to the next source instead of failing.
 *
 * Encoding is response-only, so it is deliberately unimplemented.
 */
object EnResponseSerializer : KSerializer<EnResponse> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("EnResponse")

    override fun deserialize(decoder: Decoder): EnResponse {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("EnResponse can only be decoded from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> EnResponse(
                element.map { jsonDecoder.json.decodeFromJsonElement(EnEntry.serializer(), it) },
            )
            is JsonObject -> EnResponse(
                listOf(jsonDecoder.json.decodeFromJsonElement(EnEntry.serializer(), element)),
            )
            else -> EnResponse(emptyList())
        }
    }

    override fun serialize(encoder: Encoder, value: EnResponse): Unit =
        throw SerializationException("EnResponse is a response type and is never written")
}
