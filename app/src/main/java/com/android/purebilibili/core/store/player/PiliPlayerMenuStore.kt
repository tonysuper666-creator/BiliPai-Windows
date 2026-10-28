package com.android.purebilibili.core.store.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.android.purebilibili.core.store.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Player amplification is independent of Android's music-stream volume. */
object PiliPlayerMenuStore {
    private val amplificationKey = floatPreferencesKey("pili_player_amplification")
    private val waitForEndKey = booleanPreferencesKey("pili_sleep_wait_for_end")
    private val exitAppKey = booleanPreferencesKey("pili_sleep_exit_app")
    private val cdnServiceKey = stringPreferencesKey("pili_player_cdn_service")
    fun cdnService(context: Context): Flow<String> = context.settingsDataStore.data.map { it[cdnServiceKey] ?: "BASE" }
    suspend fun setCdnService(context: Context, service: String) {
        context.settingsDataStore.edit { it[cdnServiceKey] = service }
    }

    fun amplification(context: Context): Flow<Float> = context.settingsDataStore.data.map {
        (it[amplificationKey] ?: 1f).coerceIn(1f, 3f)
    }
    suspend fun setAmplification(context: Context, value: Float) {
        context.settingsDataStore.edit { it[amplificationKey] = value.coerceIn(1f, 3f) }
    }
    fun sleepWaitForEnd(context: Context): Flow<Boolean> = context.settingsDataStore.data.map { it[waitForEndKey] ?: false }
    suspend fun setSleepWaitForEnd(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[waitForEndKey] = value }
    }
    fun sleepExitApp(context: Context): Flow<Boolean> = context.settingsDataStore.data.map { it[exitAppKey] ?: false }
    suspend fun setSleepExitApp(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[exitAppKey] = value }
    }
    fun reversedPages(context: Context, bvid: String): Flow<Boolean> =
        context.settingsDataStore.data.map { it[booleanPreferencesKey("pili_player_reverse_$bvid")] ?: false }
    suspend fun setReversedPages(context: Context, bvid: String, value: Boolean) {
        context.settingsDataStore.edit { it[booleanPreferencesKey("pili_player_reverse_$bvid")] = value }
    }
}
