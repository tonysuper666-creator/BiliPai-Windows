package com.bilipai.desktop.diagnostics

import com.android.purebilibili.core.util.DesktopDiagnosticCollector
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v0.4.6 shares the immutable redaction patterns across logging threads. The sanitizer output is unchanged:
 * every case below was derived from the pre-0.4.6 sanitizer text (same patterns, same replacement order).
 *
 * These are behaviour-preservation (characterization) tests: they pass before and after the refactor and
 * guard against a pattern, replacement or rule order drifting later. They do not demonstrate a fixed defect,
 * and java.util.regex.Pattern was already thread-safe, so the multi-thread case only checks shared use.
 */
class DesktopLogRedactionPatternsTest {
    private val cases = listOf(
        "Cookie: SESSDATA=abc123; bili_jct=deadbeef; DedeUserID=4567890" to "Cookie: ***",
        "Set-Cookie: sid=abc; Path=/" to "Set-Cookie: ***",
        "GET https://api.example.test/x?access_token=tok123&refresh_token=ref456&ts=1" to
            "GET https://api.example.test/x?access_token=***&refresh_token=***&ts=1",
        "Authorization: Bearer abcdef.ghi" to "Authorization: ***",
        "login password=hunter2,sms_code=123456 done" to "login password=***,sms_code=*** done",
        """{"mid": 12345678, "uid":99, "face":"https://i0.example.test/a.jpg", "name":"Alice", "tel":"13800138000"}""" to
            """{"mid":***, "uid":***, "face":"***", "name":"A***", "tel":"***"}""",
        "user mid=12345678 phone 13800138000 mail alice@example.test" to "user mid=*** phone 1********** mail al***@example.test",
        // Order-sensitive: the mid and uid rules run before the phone rule, so an eleven digit id is fully redacted.
        "mid=13800138000 uid=13800138000" to "mid=*** uid=***",
        "client 192.168.1.20 and 999.1.1.1 later" to "client 192.***.***.* and 999.1.1.1 later",
        "mac 00-1A-2B-3C-4D-5E ok" to "mac **:**:**:**:**:** ok",
        "file /data/user/0/com.example/files/x /home/alice/docs /Users/bob/Library" to
            "file /data/user/0/***/files/x /home/***/docs /Users/***/Library",
        "device_id=abc123 android_id=ff00 imei=1234" to "device_id=*** android_id=*** imei=***",
        "open BV1xx411c7mD av123456 cid=987654 room_id=1122 season_id=33 ep_id=44" to
            "open BV*** av*** cid=*** room_id=*** season_id=*** ep_id=***",
        """keyword=secret+words {"keyword":"abc"} Search: hello world""" to """keyword=*** {"keyword":"***"} Search: ***""",
        "content=draft text" to "content=***",
        """video_title=Hello World {"title":"Long title here"}""" to """video_title=He*** World {"title":"Lo***"}""",
        "plain message without secrets: loaded 12 items in 34 ms" to "plain message without secrets: loaded 12 items in 34 ms",
    )

    @Test
    fun sanitizerKeepsEveryRedactionRuleAndTheirOrder() {
        cases.forEach { (input, expected) ->
            assertEquals(expected, DesktopDiagnosticCollector.sanitizeMessage(input), input)
        }
    }

    @Test
    fun sharedPatternsGiveTheSameResultFromManyThreads() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                pool.submit(Callable { (1..200).all { cases.all { (input, expected) -> DesktopDiagnosticCollector.sanitizeMessage(input) == expected } } })
            }
            results.forEach { assertEquals(true, it.get(60, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }
}
