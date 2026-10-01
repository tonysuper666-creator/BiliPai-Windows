package com.bilipai.desktop.ui

import com.android.purebilibili.data.repository.DesktopOriginalVideoLoadProtocol

/** One immutable request's SAME load environment/protocol and primary Store views.
 * Every getter/setter is required to use receipt + entry admission. No credential,
 * primary account or WBI state is retained here. Bootstrap flag is the existing
 * Root visitor generation's actual flag, not proof of Android device activation.
 */
internal class DesktopOriginalVideoMetadataEnvironment(
    val load:DesktopOriginalVideoLoadProtocolEnvironment,
    val protocol:DesktopOriginalVideoLoadProtocol,
    val hasPrimarySession:()->Boolean,
    val hasPrimaryCsrf:()->Boolean,
    val hasPrimaryBuvid:()->Boolean,
    val hasPrimaryAccessToken:()->Boolean,
    val isBuvidInitialized:()->Boolean,
    val updatePrimaryVip:(Boolean)->Unit,
) {
    fun assertOwned() = load.assertOwned()
}
