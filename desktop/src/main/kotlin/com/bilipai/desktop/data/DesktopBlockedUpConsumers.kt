package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.DynamicItem

/** Author field used by the original DynamicViewModel block action; explicit detail remains accessible. */
internal fun desktopVisibleDynamicItems(rows: List<DynamicItem>, blockedMids: Set<Long>): List<DynamicItem> =
    rows.filterNot { it.modules.module_author?.mid in blockedMids }
