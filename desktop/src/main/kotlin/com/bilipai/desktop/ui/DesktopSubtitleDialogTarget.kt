package com.bilipai.desktop.ui

/** A manual import belongs to the source and account captured when the dialog opened. */
internal data class DesktopSubtitleDialogTarget(val bvid: String, val cid: Long,
    val sourceVersion: Long, val sessionEpoch: Long) {
    fun matches(bvid: String?, cid: Long?, sourceVersion: Long?, sessionEpoch: Long, owned: Boolean) =
        owned && this.bvid == bvid && this.cid == cid && this.sourceVersion == sourceVersion && this.sessionEpoch == sessionEpoch
}
