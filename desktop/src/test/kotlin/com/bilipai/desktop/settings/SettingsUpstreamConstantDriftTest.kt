package com.bilipai.desktop.settings

import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SettingsUpstreamConstantDriftTest {
    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("desktop/upstream-core/patches").isDirectory && it.resolve("app/src/main").isDirectory }

    private fun constant(path: String, name: String): String {
        val source = repoRoot().resolve(path).readText()
        val match = Regex("""\bconst\s+val\s+${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(source)
        return assertNotNull(match, "Missing upstream constant $name in $path").groupValues[1]
    }

    private fun patchLiteral(path: String, settingId: String): String {
        val patch = repoRoot().resolve(path).readLines()
        val line = patch.single { it.startsWith("+") && settingId in it }
        val literals = Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }.toList()
        return literals.last()
    }

    @Test fun settingsConstantsMatchUpstream() {
        assertEquals(
            constant("app/src/main/java/com/android/purebilibili/feature/video/ui/components/PlayerSubtitleSettingsPanel.kt", "SUBTITLE_APPEARANCE_TITLE"),
            patchLiteral("desktop/upstream-core/patches/app/src/main/java/com/android/purebilibili/feature/settings/SettingsAdditionalDirectory.kt.patch", "playback.subtitle_appearance"),
        )
        assertEquals(
            constant("app/src/main/java/com/android/purebilibili/feature/nearby/NearbyProtocol.kt", "NEARBY_TITLE"),
            patchLiteral("desktop/upstream-core/patches/app/src/main/java/com/android/purebilibili/feature/settings/SettingsDestinationCopy.kt.patch", "title ="),
        )
    }
}
