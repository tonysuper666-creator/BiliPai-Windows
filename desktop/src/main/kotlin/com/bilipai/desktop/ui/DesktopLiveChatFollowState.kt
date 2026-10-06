package com.bilipai.desktop.ui

import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Follow intent changes on user input, never because a new batch displaced the old tail.
 * The fixed v0.3.0 chat uses a one-row bottom tolerance and 300 ms batched following. */
internal data class DesktopLiveChatFollowState(
    val following: Boolean = true,
    val revision: Long = 0,
) {
    fun userInput() = copy(following = false, revision = revision + 1)
    fun userInputSettled(nearBottom: Boolean) = copy(following = nearBottom, revision = revision + 1)
    fun resume() = copy(following = true, revision = revision + 1)
    fun mayFollow(pointerHeld: Boolean, scrolling: Boolean) = following && !pointerHeld && !scrolling
}

internal fun desktopLiveChatNearBottom(lastVisibleIndex: Int, totalItems: Int): Boolean =
    totalItems == 0 || (lastVisibleIndex >= 0 && lastVisibleIndex >= totalItems - 2)

internal data class DesktopLiveChatRow<T : Any>(val key: Long, val item: T)

/** The session keeps at most 200 immutable message instances. Keep retained instances'
 * keys when it drops the oldest items, including equal messages with no server ID.
 * Occurrence queues also keep keys unique if the same instance appears twice. */
internal class DesktopLiveChatTimeline<T : Any> {
    private var sequence = 0L
    private var previous = emptyList<DesktopLiveChatRow<T>>()

    fun update(messages: List<T>): List<DesktopLiveChatRow<T>> {
        val retained = IdentityHashMap<T, ArrayDeque<DesktopLiveChatRow<T>>>()
        previous.forEach { retained.getOrPut(it.item) { ArrayDeque() }.addLast(it) }
        return messages.map { item ->
            retained[item]?.pollFirst() ?: DesktopLiveChatRow(++sequence, item)
        }.also { previous = it }
    }
}
