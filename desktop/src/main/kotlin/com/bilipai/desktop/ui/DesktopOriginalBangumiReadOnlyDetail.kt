package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BangumiDetail
import com.android.purebilibili.data.model.response.BangumiEpisode
import com.bilipai.desktop.data.DesktopHomeNavRequestReceipt

/** An admitted original season/selected-episode DTO for presentation only.
 * No Binding, API, player, request launcher or credentials are exposed. The
 * post-begin projection is freshly admitted to its actual PGC request; the
 * initial read witness is never retagged to a new Store token. */
internal class DesktopOriginalBangumiReadOnlyDetail internal constructor(
    val detail: BangumiDetail,
    val currentEpisode: BangumiEpisode,
    val currentEpisodeIndex: Int,
    internal val intentRevision: Long,
    internal val primaryInstallation: DesktopHomeNavRequestReceipt,
    private val admission: DesktopOriginalVideoMetadataAdmission,
) {
    /** Normal request completion may retain readonly data. Cancellation,
     * replaced request/token/intent, account or presenter retirement may not. */
    fun isCurrent(): Boolean = admission.isCurrent()
}
