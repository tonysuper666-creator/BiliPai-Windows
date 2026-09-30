package com.bilipai.desktop.plugins.js

import com.bilipai.desktop.ui.runDesktopJsMediaImageFixture
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DesktopJsRemoteMediaTest {
    @Test fun actualOriginalRemoteImportAndCancellation(): Unit = runDesktopJsPublicHttpFixture()
    @Test fun actualBoundedSkiaCandidateFallbackAndStringParameters(): Unit = runDesktopJsMediaImageFixture()
    @Test @Tag("js-worker") fun actualImmutableRemoteInstallationAndImageAuthority(): Unit {
        val root = Path.of(requireNotNull(System.getProperty("bilipai.js.workerResources")))
        runDesktopJsRemoteRepositoryFixture(arrayOf(root.toAbsolutePath().normalize().toString()))
    }
}
