package kotlinx.coroutines.android

import androidx.compose.runtime.withFrameNanos

/** Desktop stand-in for kotlinx-coroutines-android's awaitFrame: resumes on the next Compose frame. */
suspend fun awaitFrame(): Long = withFrameNanos { it }
