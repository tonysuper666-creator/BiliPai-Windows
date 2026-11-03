package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.appearance.DesktopThemeSettings
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The message pages (私信/消息 from the home avatar drawer or the profile page) sit behind
 * DesktopOriginalMessagePageRootHost's CommunityLoginGate: signed out, the gate is shown instead of the
 * message entry, its button opens login, and signing in composes the pages for that account. */
class DesktopMessageLoginGateTest {
    @Test fun signedOutMessageEntryShowsTheLoginGateAndOpensLogin() {
        check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        val folder = Files.createTempDirectory("bp-msg-gate-")
        val sessions = DesktopSessionStore(folder.resolve("synthetic-session.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        var loginRequests = 0
        val composedFor = mutableListOf<Long>()
        val scene = ImageComposeScene(900, 700, Density(1.5f)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("zh-CN"),
            ) {
                Box(Modifier.fillMaxSize()) {
                    CommunityLoginGate(repository, { loginRequests++ }) { mid ->
                        composedFor += mid
                        Text("message pages")
                    }
                }
            }
        }
        var nanos = 0L
        fun settle() = repeat(6) { nanos += 30_000_000L; scene.render(nanos).close() }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun hasText(text: String) = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        try {
            settle()
            assertTrue(hasText("登录后查看账号内容"), "Signed out, the message entry shows the login gate")
            assertTrue(composedFor.isEmpty(), "No message page is composed without an account")
            val login = nodes().filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null }
                .single { node -> flatten(node).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "扫码登录" } == true } }
            requireNotNull(login.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            settle()
            assertEquals(1, loginRequests, "The gate's button opens login")

            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only-gate", "bili_jct" to "synthetic-only-csrf"), AccountSummary(64, "Gate fixture", ""))
            settle()
            assertTrue(!hasText("登录后查看账号内容") && hasText("message pages"), "Signed in, the message pages replace the gate")
            assertEquals(64L, composedFor.last(), "The pages are composed for the signed-in account")
        } finally {
            scene.close()
            folder.toFile().deleteRecursively()
        }
    }
}
