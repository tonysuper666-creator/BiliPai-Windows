package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.ReplyFansDetail
import com.android.purebilibili.data.model.response.ReplyMember
import com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc
import java.io.ByteArrayOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real generated member decoder and serializable shared model. The wire
 * fixture encodes server field numbers; no transport, account or UI is used. */
class DesktopReplyMedalColorTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun actualMemberDecoderRetainsBothServerColorsAndExistingIdentity() {
        val member = decode(number(1, 88) + text(18, "Fixture medal") + number(19, 12) +
            number(27, 0xFF00FF00L) + text(99, "unknown future field") +
            number(20, 0xFF1298EFL) + number(32, 1))
        val medal = assertNotNull(member.fansDetail)
        assertEquals(88L, medal.uid)
        assertEquals("Fixture medal", medal.medalName)
        assertEquals(12, medal.level)
        assertEquals(0xFF1298EFL, medal.medalColor)
        assertEquals(0xFF00FF00L, medal.medalColorName)
    }

    @Test fun absentOptionalColorFieldsKeepTheExistingLevelBasedMedal() {
        val member = decode(number(1, 88) + text(18, "Old medal") + number(19, 7))
        val medal = assertNotNull(member.fansDetail)
        assertEquals("Old medal", medal.medalName)
        assertEquals(7, medal.level)
        assertEquals(0L, medal.medalColor)
        assertEquals(0L, medal.medalColorName)
    }

    @Test fun serverColorsDoNotCreateAMedalWithoutItsExistingNameAndLevel() {
        for (bytes in listOf(
            number(1, 88) + number(19, 7) + number(20, 0xFF1298EFL),
            number(1, 88) + text(18, "No level") + number(27, 0xFF00FF00L),
        )) assertNull(decode(bytes).fansDetail)
    }

    @Test fun restStringNumbersAndJsonRoundTripPreserveUnsignedArgbSizedValues() {
        val parsed = json.decodeFromString<ReplyFansDetail>(
            """{"uid":"88","medal_name":"REST medal","level":"7","medal_color":"4279400687","medal_color_name":"4278255360","future":true}"""
        )
        assertEquals(0xFF1278EFL, parsed.medalColor)
        assertEquals(0xFF00FF00L, parsed.medalColorName)
        val encoded = json.encodeToString(parsed)
        val fields = json.parseToJsonElement(encoded).jsonObject
        assertEquals(parsed.medalColor, fields.getValue("medal_color").jsonPrimitive.long)
        assertEquals(parsed.medalColorName, fields.getValue("medal_color_name").jsonPrimitive.long)
        assertEquals(parsed, json.decodeFromString<ReplyFansDetail>(encoded))
    }

    private fun decode(bytes: ByteArray): ReplyMember {
        val codec = DesktopDynamicCommentGrpc({ _, _ -> error("No transport expected") }, {})
        val method = codec.javaClass.getDeclaredMethod("parseMember", ByteArray::class.java)
            .apply { isAccessible = true }
        return method.invoke(codec, bytes) as ReplyMember
    }

    private fun number(field: Int, value: Long): ByteArray = ByteArrayOutputStream().apply {
        wireVarint((field.toLong() shl 3))
        wireVarint(value)
    }.toByteArray()

    private fun text(field: Int, value: String): ByteArray = ByteArrayOutputStream().apply {
        val bytes = value.toByteArray(Charsets.UTF_8)
        wireVarint((field.toLong() shl 3) or 2L)
        wireVarint(bytes.size.toLong())
        write(bytes)
    }.toByteArray()

    private fun ByteArrayOutputStream.wireVarint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            write(((remaining and 127L) or 128L).toInt())
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
    }
}
