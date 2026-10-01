package com.android.purebilibili.core.store
enum class TabletSecondaryDefaultTab(val value: Int, val label: String) {
    COMMENTS(0, "评论"),
    RELATED(1, "推荐");

    companion object {
        fun fromValue(value: Int): TabletSecondaryDefaultTab =
            entries.find { it.value == value } ?: RELATED
    }
}

