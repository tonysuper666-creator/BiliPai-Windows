package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.repository.ContentRequestException
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.player.PlayerFailure
import com.bilipai.desktop.player.PlayerFailureKind
import java.io.IOException
import kotlin.test.*

class DesktopOriginalPlaybackFailureTest {
    @Test fun typedAuthenticationAndRestrictionAreNotGenericIoRetries() {
        for (code in listOf(-101, -403, -404, -10403, -62002)) {
            val error = assertIs<VideoLoadError.ApiError>(desktopOriginalVideoLoadError(BiliApiException(code, "synthetic")))
            assertEquals(code, error.code)
            val classified = desktopOriginalApiFailure(error)
            assertEquals(if (code == -101) PlaybackFailureReason.Authentication else PlaybackFailureReason.Restricted, classified.reason)
            assertFalse(classified.automaticRecovery)
            assertFalse(desktopOriginalVideoLoadCanRetry(BiliApiException(code, "synthetic")))
        }
        val shared = assertIs<VideoLoadError.ApiError>(desktopOriginalVideoLoadError(ContentRequestException(-101, "synthetic")))
        assertEquals(-101, shared.code)
    }

    @Test fun ordinaryIoAndTimeoutKeepTheirOriginalNetworkRecovery() {
        assertSame(VideoLoadError.NetworkError, desktopOriginalVideoLoadError(IOException("synthetic")))
        for (error in listOf(VideoLoadError.NetworkError, VideoLoadError.Timeout)) {
            val evidence = desktopOriginalApiFailure(error)
            assertEquals(PlaybackFailureReason.Network, evidence.reason)
            assertTrue(evidence.automaticRecovery)
        }
    }

    @Test fun existingHttpAndCooldownAvailabilityIsPreservedWithoutAutomaticRequestLoop() {
        for (code in listOf(412, 429)) {
            val error = assertIs<VideoLoadError.ApiError>(desktopOriginalVideoLoadError(BiliApiException(code, "synthetic")))
            assertEquals(code, error.code)
            assertTrue(desktopOriginalVideoLoadCanRetry(BiliApiException(code, "synthetic")))
            assertFalse(desktopOriginalApiFailure(error).automaticRecovery)
        }
        for (error in listOf(VideoLoadError.WbiSignatureError, VideoLoadError.RateLimited(1_000, "BVfixture"),
            VideoLoadError.GlobalCooldown(1_000, 3))) assertFalse(desktopOriginalApiFailure(error).automaticRecovery)
    }

    @Test fun onlyProvenPremiumAudioFailureCanRecoverLocalAudioOutput() {
        val local = PlayerFailure(PlayerFailureKind.AUDIO_OUTPUT, -14, "Audio output initialization failed", sourceVersion = 3, attemptId = 1)
        val disabled = desktopOriginalNativeFailure(local, false)
        assertEquals(PlaybackFailureReason.Audio, disabled.reason)
        assertFalse(disabled.automaticRecovery)
        assertTrue(desktopOriginalNativeFailure(local, true).premiumAudio)
        assertFalse(desktopOriginalNativeFailure(local.copy(kind = PlayerFailureKind.FILE_IO), false).automaticRecovery)
        assertFalse(desktopOriginalNativeFailure(local.copy(kind = PlayerFailureKind.VIDEO_OUTPUT), false).automaticRecovery)
    }

    @Test fun actualNativeEvidenceKeepsSafeMessageAndDoesNotInferDecoderFromArbitraryText() {
        val failure = PlayerFailure(PlayerFailureKind.DECODER, null, "视频解码失败", sourceVersion = 3, attemptId = 1)
        val evidence = desktopOriginalNativeFailure(failure, false)
        assertEquals(PlaybackFailureReason.Decoder, evidence.reason)
        assertTrue(evidence.decoder)
        assertEquals(0, evidence.code)
        assertEquals(failure.safeMessage, evidence.message)
        val unknown = desktopOriginalNativeFailure(failure.copy(kind = PlayerFailureKind.UNKNOWN), false)
        assertFalse(unknown.decoder)
        assertEquals(PlaybackFailureReason.Source, unknown.reason)
    }
}
