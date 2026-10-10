package com.davidvancl.espcontrol

import com.davidvancl.espcontrol.protocol.Message
import com.davidvancl.espcontrol.protocol.Protocol
import com.davidvancl.espcontrol.protocol.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Examples from esp-device-control/DESIGN.md. */
class ProtocolTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun encodesRequests() {
        assertEquals(
            json("""{"t":"hello","seq":1,"proto":1,"token":"9f2c"}"""),
            json(Protocol.encode(Request.Hello(token = "9f2c"), 1)),
        )
        assertEquals(json("""{"t":"hello","seq":1,"proto":1}"""), json(Protocol.encode(Request.Hello(), 1)))
        assertEquals(json("""{"t":"get","seq":2}"""), json(Protocol.encode(Request.Get, 2)))
        assertEquals(
            json("""{"t":"set","seq":3,"id":"green","v":40}"""),
            json(Protocol.encode(Request.Set("green", JsonPrimitive(40)), 3)),
        )
        assertFalse("v" in json(Protocol.encode(Request.Set("reset"), 4)).jsonObject)
        assertEquals(json("""{"t":"pin","seq":5,"pin":"4821"}"""), json(Protocol.encode(Request.Pin("4821"), 5)))
    }

    @Test
    fun decodesInfo() {
        val info = Protocol.decode(
            """{"t":"info","seq":1,"proto":1,"id":"a1b2c3","name":"Ikarus","type":"ikarus","fw":"0.0.3",
               "authed":true,"get":["key"],"set":["left2","green"],"future":42}"""
        ) as Message.Info
        assertEquals(1, info.seq)
        assertEquals("a1b2c3", info.id)
        assertEquals("ikarus", info.type)
        assertEquals(listOf("key"), info.get)
        assertEquals(listOf("left2", "green"), info.set)
    }

    @Test
    fun decodesUnauthedInfoWithoutLists() {
        val info = Protocol.decode(
            """{"t":"info","seq":1,"proto":1,"id":"a1b2c3","name":"Ikarus","type":"ikarus","fw":"0.0.3","authed":false}"""
        ) as Message.Info
        assertFalse(info.authed)
        assertEquals(emptyList<String>(), info.get)
    }

    @Test
    fun decodesDeviceMessages() {
        assertEquals(
            Message.Event(values = mapOf("key" to JsonPrimitive(true))),
            Protocol.decode("""{"t":"event","values":{"key":true}}"""),
        )
        assertEquals(
            Message.Err(seq = 3, code = "invalid", msg = "value rejected"),
            Protocol.decode("""{"t":"err","seq":3,"code":"invalid","msg":"value rejected"}"""),
        )
        assertEquals(
            Message.Pairing(state = "waiting", method = "pin", timeout = 60),
            Protocol.decode("""{"t":"pairing","state":"waiting","method":"pin","timeout":60}"""),
        )
        assertEquals(Message.Paired(token = "abc"), Protocol.decode("""{"t":"paired","token":"abc"}"""))
        assertEquals(Message.Ack(seq = 7), Protocol.decode("""{"t":"ack","seq":7}"""))
    }

    @Test
    fun ignoresUnknownAndInvalidMessages() {
        assertNull(Protocol.decode("""{"t":"something_new","seq":1}"""))
        assertNull(Protocol.decode("not json"))
        assertNull(Protocol.decode("""{"seq":1}"""))
    }
}
