package com.bilipai.desktop.settings

import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.home.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopHomeCardPreferencesTest {
    private fun root()=Files.createTempDirectory("bp-hc-test-")
    private fun preferences(path:java.nio.file.Path)=DesktopHomeCardPreferences(DesktopPluginContext(DesktopPluginStore(path)))

    @Test fun originalPersistedDefaultsDoNotCreateAFile():Unit=runBlocking {
        val root=root();val preferences=preferences(root)
        assertEquals(DesktopHomeCardSettings(0,0,HomeFeedCardWidthPreset.AUTO,HomeFeedCardStyle.BILIPAI),preferences.initialSettings())
        assertEquals(preferences.initialSettings(),preferences.settings.first())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }
    @Test fun unknownEnumValuesUseOriginalFallbackAndDoNotRewriteDisk():Unit=runBlocking {
        val root=root();val file=root.resolve("plugin-settings.json")
        val encoded="""{"settings":{"home_feed_card_width_preset":999,"home_feed_card_style":-7,"grid_column_count":-2}}"""
        Files.writeString(file,encoded);val preferences=preferences(root)
        assertEquals(HomeFeedCardWidthPreset.AUTO,preferences.initialSettings().homeFeedCardWidthPreset)
        assertEquals(HomeFeedCardStyle.BILIPAI,preferences.initialSettings().homeFeedCardStyle)
        assertEquals(-2,preferences.initialSettings().gridColumnCount)
        assertEquals(encoded,Files.readString(file))
    }
    @Test fun realGlobalWritesKeepIndependentCompactMemoryAndUnrelatedKeys():Unit=runBlocking {
        val root=root();Files.writeString(root.resolve("plugin-settings.json"),"""{"settings":{"app_language":"en","grid_column_count_compact":3},"plugin_prefs":{"unrelated":"保留😀"}}""")
        val first=preferences(root);val second=preferences(root)
        first.setGridColumnCount(5);first.setWidthPreset(HomeFeedCardWidthPreset.WIDE);second.setStyle(HomeFeedCardStyle.OFFICIAL)
        assertEquals(DesktopHomeCardSettings(5,3,HomeFeedCardWidthPreset.WIDE,HomeFeedCardStyle.OFFICIAL),first.settings.first())
        second.setGridColumnCountCompact(2);assertEquals(2,first.settings.first().gridColumnCountCompact)
        val document=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertEquals("en",document["settings"]!!.jsonObject["app_language"]!!.jsonPrimitive.content)
        assertEquals("保留😀",document["plugin_prefs"]!!.jsonObject["unrelated"]!!.jsonPrimitive.content)
        assertFalse(document.containsKey("accounts"));assertFalse(document.containsKey("guest"))
    }
    @Test fun failedAtomicReplacementDoesNotPublishNewSettings():Unit=runBlocking {
        val root=root();val preferences=preferences(root);val before=preferences.initialSettings()
        Files.createDirectory(root.resolve("plugin-settings.json"))
        assertFailsWith<Exception>{preferences.setGridColumnCount(4)}
        assertEquals(before,preferences.initialSettings());assertEquals(before,preferences.settings.first())
        Files.list(root).use{paths->assertEquals(listOf("plugin-settings.json"),paths.map{it.fileName.toString()}.toList())}
    }
    @Test fun frozenRestoreGenerationCannotOverwriteFreshSettings():Unit=runBlocking {
        val root=root();val oldStore=DesktopPluginStore(root);val old=DesktopHomeCardPreferences(DesktopPluginContext(oldStore))
        old.setGridColumnCount(2);oldStore.freezeWrites()
        Files.writeString(root.resolve("plugin-settings.json"),"""{"settings":{"grid_column_count":6}}""")
        val fresh=preferences(root)
        assertFailsWith<IllegalStateException>{old.setStyle(HomeFeedCardStyle.CURRENT)}
        assertEquals(6,fresh.initialSettings().gridColumnCount)
        assertEquals(6,Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject["grid_column_count"]!!.jsonPrimitive.int)
    }
    @Test fun invalidNamespaceIsRejectedWithoutMutation() {
        val root=root();val file=root.resolve("plugin-settings.json");val value="""{"settings":[1,2]}""";Files.writeString(file,value)
        assertFailsWith<IllegalArgumentException>{preferences(root)};assertEquals(value,Files.readString(file))
    }
    @Test fun cancellationBeforeIoEntryDoesNotPersist():Unit=runBlocking {
        val root=root();val preferences=preferences(root)
        val job=launch(start=CoroutineStart.LAZY){preferences.setGridColumnCount(4)};job.cancel();job.join()
        assertEquals(0,preferences.initialSettings().gridColumnCount);assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }
    @Test fun originalWidthBreakpointsAndCompactMemorySelectCorrectScope() {
        val cases=listOf(599 to WindowWidthSizeClass.Compact,600 to WindowWidthSizeClass.Medium,839 to WindowWidthSizeClass.Medium,
            840 to WindowWidthSizeClass.Expanded,1199 to WindowWidthSizeClass.Expanded,1200 to WindowWidthSizeClass.Large,
            1599 to WindowWidthSizeClass.Large,1600 to WindowWidthSizeClass.ExtraLarge)
        cases.forEach{(width,expected)->assertEquals(expected,resolveWindowWidthSizeClass(width.dp))
            assertEquals(if(expected==WindowWidthSizeClass.Compact)2 else 5,resolveHomeFeedStoredColumnCount(expected,2,5))}
        assertEquals(1280.dp,resolveHomeFeedMaxContentWidth())
    }
    @Test fun originalAutomaticBoundsAndFixedColumnPriorityRemainIntact() {
        assertEquals(2,resolveHomeFeedGridColumns(400,0,0,HomeFeedCardWidthPreset.AUTO,WindowWidthSizeClass.Compact))
        assertEquals(4,resolveHomeFeedGridColumns(800,0,0,HomeFeedCardWidthPreset.AUTO,WindowWidthSizeClass.Medium))
        assertEquals(2,resolveHomeFeedGridColumns(800,0,0,HomeFeedCardWidthPreset.ULTRA_WIDE,WindowWidthSizeClass.Medium))
        assertEquals(6,resolveHomeFeedGridColumns(1280,0,0,HomeFeedCardWidthPreset.COMPACT,WindowWidthSizeClass.Expanded))
        assertEquals(8,resolveHomeFeedGridColumns(1280,0,0,HomeFeedCardWidthPreset.COMPACT,WindowWidthSizeClass.ExtraLarge))
        assertEquals(7,resolveHomeFeedGridColumns(400,0,7,HomeFeedCardWidthPreset.ULTRA_WIDE,WindowWidthSizeClass.Compact))
        assertEquals(1,resolveHomeFeedGridColumns(299,0,0,HomeFeedCardWidthPreset.ULTRA_WIDE,WindowWidthSizeClass.Compact))
    }
    @Test fun originalCoverExceptionsSpacingAndDenseTitlePolicyRemainIntact() {
        assertEquals(16f/10f,resolveHomeFeedCoverAspectRatio(HomeFeedCardStyle.BILIPAI,2,WindowWidthSizeClass.Medium))
        assertEquals(16f/9f,resolveHomeFeedCoverAspectRatio(HomeFeedCardStyle.BILIPAI,2,WindowWidthSizeClass.Expanded))
        assertEquals(16f/10f,resolveHomeFeedCoverAspectRatio(HomeFeedCardStyle.OFFICIAL,1,WindowWidthSizeClass.Medium))
        assertEquals(16f/9f,resolveHomeFeedCoverAspectRatio(HomeFeedCardStyle.OFFICIAL,1,WindowWidthSizeClass.Expanded))
        assertEquals(4f/3f,resolveHomeFeedCoverAspectRatio(HomeFeedCardStyle.OFFICIAL,2,WindowWidthSizeClass.Expanded))
        HomeFeedCardStyle.entries.forEach{style->val layout=resolveHomeFeedCardLayout(style,6,WindowWidthSizeClass.Expanded)
            assertEquals(6,layout.outerPaddingDp);assertEquals(6,layout.itemSpacingDp);assertEquals(6,layout.verticalItemSpacingDp)
            assertEquals(1,layout.titleMinLines);assertEquals(2,layout.titleMaxLines)}
        assertEquals(2,resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI,2,WindowWidthSizeClass.Medium).titleMinLines)
    }
}
