package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.data.model.response.DynamicDetailData

/** Keep only fields confirmed while this read was outstanding; richer content
 * and untouched server fields still come from the original detail response. */
internal fun mergeDesktopDynamicDetailReadback(
    incoming: DynamicDetailData, current: DynamicDetailData?,
    likeChanged: Boolean, forwardChanged: Boolean, foldChanged: Boolean, removed: Boolean,
): DynamicDetailData {
    if (removed) return incoming.copy(item = null)
    val fresh = incoming.item ?: return incoming
    val existing = current?.item?.takeIf { it.id_str == fresh.id_str } ?: return incoming
    val oldStat = existing.modules.module_stat
    val newStat = fresh.modules.module_stat
    val stat = if (newStat != null && oldStat != null) newStat.copy(
        like = if (likeChanged) newStat.like.copy(count = oldStat.like.count, status = oldStat.like.status) else newStat.like,
        forward = if (forwardChanged) newStat.forward.copy(count = oldStat.forward.count) else newStat.forward,
    ) else if (likeChanged || forwardChanged) oldStat ?: newStat else newStat
    val updated: DynamicItem = fresh.copy(modules = fresh.modules.copy(
        module_stat = stat,
        module_fold = if (foldChanged) existing.modules.module_fold else fresh.modules.module_fold,
    ), visible = if (foldChanged) existing.visible else fresh.visible)
    return incoming.copy(item = updated)
}
