package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.DesktopDynamicCardOperations

/** Required Windows surface/callbacks for the original composer.
 * Operations and emotes belong to the existing card epoch. Nothing creates an
 * account, cache, store, API or original draft schema here.
 */
internal interface DesktopDynamicEditorPlatform {
    val emotes: DesktopDynamicEmotes
    fun isOwned(): Boolean
    fun pickImages(maxItems: Int, onSelected: (List<String>) -> Unit)
    fun chooseDateAndTime(initialMillis: Long, onSelected: (year: Int, monthZeroBased: Int, day: Int, hour: Int, minute: Int) -> Unit)
    suspend fun searchMentionUsers(query: String): Result<List<MentionSearchUser>>
    suspend fun searchPublishTopics(query: String): Result<List<DynamicTopicSearchItem>>
    suspend fun createVote(title: String, options: List<String>, description: String, choiceCount: Int, durationDays: Int): Result<DynamicCreatedVote>
    suspend fun createReserve(title: String, livePlanStartTimeSeconds: Long, subType: Int): Result<DynamicCreatedReserve>
}
internal val LocalDesktopDynamicEditorBindings = staticCompositionLocalOf<DesktopDynamicEditorPlatform> {
    error("The original dynamic editor requires a current desktop card owner")
}

internal class DesktopDynamicEditorOperationsBinding(
    private val operations: DesktopDynamicCardOperations,
    override val emotes: DesktopDynamicEmotes,
    private val imagePicker: (Int, (List<String>) -> Unit) -> Unit,
    private val dateTimePicker: (Long, (Int, Int, Int, Int, Int) -> Unit) -> Unit,
    private val stillOwned: () -> Boolean = { true },
) : DesktopDynamicEditorPlatform {
    override fun isOwned() = operations.isOwned() && stillOwned()
    override fun pickImages(maxItems: Int, onSelected: (List<String>) -> Unit) {
        if (isOwned()) imagePicker(maxItems) { selected -> if (isOwned()) onSelected(selected) }
    }
    override fun chooseDateAndTime(initialMillis: Long, onSelected: (Int, Int, Int, Int, Int) -> Unit) {
        if (isOwned()) dateTimePicker(initialMillis) { year, month, day, hour, minute ->
            if (isOwned()) onSelected(year, month, day, hour, minute)
        }
    }
    override suspend fun searchMentionUsers(query: String) = operations.searchMentionUsers(query)
    override suspend fun searchPublishTopics(query: String) = operations.searchPublishTopics(query)
    override suspend fun createVote(title: String, options: List<String>, description: String, choiceCount: Int, durationDays: Int) =
        operations.createVote(title, options, description, choiceCount, durationDays)
    override suspend fun createReserve(title: String, livePlanStartTimeSeconds: Long, subType: Int) =
        operations.createReserve(title, livePlanStartTimeSeconds, subType)
}
