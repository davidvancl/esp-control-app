package com.davidvancl.espcontrol.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Protocol v1, see esp-device-control/DESIGN.md. */
const val PROTOCOL_VERSION = 1

/** Messages sent by the app. `seq` is added by [Protocol.encode]. */
@Serializable
sealed interface Request {
    @Serializable
    @SerialName("hello")
    data class Hello(val proto: Int = PROTOCOL_VERSION, val token: String? = null) : Request

    @Serializable
    @SerialName("get")
    data object Get : Request

    @Serializable
    @SerialName("set")
    data class Set(val id: String, val v: JsonElement? = null) : Request

    @Serializable
    @SerialName("pair")
    data object Pair : Request

    @Serializable
    @SerialName("pin")
    data class Pin(val pin: String) : Request

    @Serializable
    @SerialName("unpair")
    data object Unpair : Request

    /** The device replies `ack` and restarts about 0.5 s later. */
    @Serializable
    @SerialName("restart")
    data object Restart : Request
}

/** Messages sent by the device. Unsolicited messages have no `seq`. */
@Serializable
sealed interface Message {
    val seq: Int?

    @Serializable
    @SerialName("info")
    data class Info(
        override val seq: Int? = null,
        val proto: Int,
        val id: String,
        val name: String = "",
        val type: String = "",
        val fw: String = "",
        val authed: Boolean = false,
        val get: List<String> = emptyList(),
        val set: List<String> = emptyList(),
    ) : Message

    @Serializable
    @SerialName("state")
    data class State(override val seq: Int? = null, val values: Map<String, JsonElement> = emptyMap()) : Message

    @Serializable
    @SerialName("ack")
    data class Ack(override val seq: Int? = null) : Message

    @Serializable
    @SerialName("err")
    data class Err(override val seq: Int? = null, val code: String, val msg: String = "") : Message

    @Serializable
    @SerialName("event")
    data class Event(override val seq: Int? = null, val values: Map<String, JsonElement> = emptyMap()) : Message

    @Serializable
    @SerialName("pairing")
    data class Pairing(
        override val seq: Int? = null,
        val state: String,
        val method: String? = null,
        val timeout: Int? = null,
    ) : Message

    @Serializable
    @SerialName("paired")
    data class Paired(override val seq: Int? = null, val token: String) : Message
}

object ErrorCode {
    const val BAD_REQUEST = "bad_request"
    const val UNKNOWN_ID = "unknown_id"
    const val INVALID = "invalid"
    const val READONLY = "readonly"
    const val AUTH = "auth"
    const val PROTO = "proto"
    const val BUSY = "busy"
    const val UNSUPPORTED = "unsupported"
}

object Protocol {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true // `proto` in hello
        explicitNulls = false // optional fields (`token`, `v`) are omitted
    }

    fun encode(request: Request, seq: Int): String {
        val body = json.encodeToJsonElement(Request.serializer(), request).jsonObject
        return JsonObject(body + ("seq" to JsonPrimitive(seq))).toString()
    }

    /** Returns `null` for invalid JSON and unknown message types, which the app ignores. */
    fun decode(text: String): Message? = try {
        json.decodeFromString(Message.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
