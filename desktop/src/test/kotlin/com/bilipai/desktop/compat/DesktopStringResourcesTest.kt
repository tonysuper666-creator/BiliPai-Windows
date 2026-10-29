package com.bilipai.desktop.compat

import android.content.res.DesktopStringTables
import android.content.res.Resources
import com.android.purebilibili.R
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Upstream app strings come from the upstream XML, in the system language with Android's fallback. */
class DesktopStringResourcesTest {
    private fun resources(locale: Locale) = Resources { locale }

    @Test fun `the default tables are the upstream simplified chinese values`() {
        val chinese = resources(Locale.SIMPLIFIED_CHINESE)
        assertEquals("BiliPai", chinese.getString(R.string.app_name))
        assertEquals("重试", chinese.getString(R.string.common_retry))
        assertEquals("2. 未成年人应在监护人指导下使用本应用。", chinese.getString(R.string.user_agreement_user_responsibility_2))
        assertEquals("已导入 3 项液态玻璃设置", chinese.getString(R.string.animation_import_success, 3))
        // A system language upstream does not translate reads the default values, as on Android.
        assertEquals("重试", resources(Locale.JAPAN).getString(R.string.common_retry))
    }

    @Test fun `an english system reads values-en with aapt escapes resolved`() {
        val english = resources(Locale.US)
        assertEquals("2. Minors should use this app under a guardian's guidance.", english.getString(R.string.user_agreement_user_responsibility_2))
        assertEquals("Imported 3 Liquid Glass settings", english.getString(R.string.animation_import_success, 3))
        assertEquals(english.getString(R.string.common_retry), resources(Locale.UK).getString(R.string.common_retry))
    }

    @Test fun `traditional chinese reads values-zh-rTW and falls back to the default for untranslated strings`() {
        val taiwan = resources(Locale.TAIWAN)
        assertEquals("重試", taiwan.getString(R.string.common_retry))
        assertEquals(listOf("zh-TW", "default"), DesktopStringTables.chain(Locale.forLanguageTag("zh-Hant")))
        assertEquals("2. 未成年人应在监护人指导下使用本应用。", taiwan.getString(R.string.user_agreement_user_responsibility_2))
        assertEquals(listOf("default"), DesktopStringTables.chain(Locale.forLanguageTag("zh-HK")))
    }

    @Test fun `an unknown id fails instead of showing an empty label`() {
        assertFailsWith<IllegalArgumentException> { resources(Locale.US).getString(0x7f13ffff) }
    }
}
