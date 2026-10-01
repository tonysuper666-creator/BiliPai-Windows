// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt
// LF SHA256 218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59
package com.bilipai.desktop.ui
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** Exact stable AppNavigation session-scoped key construction; Root window lifetime. */
internal class DesktopOriginalRootSessionKeys {
    private var lastVideoDetailOpenId = 0L
    private var lastLiveAreaDetailOpenId = 0L
    private var lastSearchOpenId = 0L
    fun decorate(key: BiliPaiNavKey): BiliPaiNavKey {
        return when (key) {
                        is BiliPaiNavKey.VideoDetail -> {
                            if (key.openId > 0L) {
                                key
                            } else {
                                val nextOpenId = maxOf(
                                    DesktopHomeClock.elapsedRealtime(),
                                    lastVideoDetailOpenId + 1L,
                                )
                                lastVideoDetailOpenId = nextOpenId
                                key.copy(openId = nextOpenId)
                            }
                        }
                        is BiliPaiNavKey.LiveAreaDetail -> {
                            if (key.openId > 0L) {
                                key
                            } else {
                                val nextOpenId = maxOf(
                                    DesktopHomeClock.elapsedRealtime(),
                                    lastLiveAreaDetailOpenId + 1L,
                                )
                                lastLiveAreaDetailOpenId = nextOpenId
                                key.copy(openId = nextOpenId)
                            }
                        }
                        is BiliPaiNavKey.Search -> {
                            if (key.openId > 0L) {
                                key
                            } else {
                                val nextOpenId = maxOf(
                                    DesktopHomeClock.elapsedRealtime(),
                                    lastSearchOpenId + 1L,
                                )
                                lastSearchOpenId = nextOpenId
                                key.copy(openId = nextOpenId)
                            }
                        }
                        BiliPaiNavKey.Search -> {
                            val nextOpenId = maxOf(
                                DesktopHomeClock.elapsedRealtime(),
                                lastSearchOpenId + 1L,
                            )
                            lastSearchOpenId = nextOpenId
                            BiliPaiNavKey.Search(openId = nextOpenId)
                        }
                        BiliPaiNavKey.LikedVideos -> {
                            BiliPaiNavKey.LikedVideos()
                        }
                        else -> key
                    }
    }
}
