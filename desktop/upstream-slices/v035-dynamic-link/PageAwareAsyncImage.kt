package com.android.purebilibili.core.ui.components

import android.graphics.drawable.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.DrawableImage
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.asPainter
import coil3.imageLoader
import coil3.request.ImageRequest

/** Defaults to normal loading outside the retained bottom-tab page scope. */
internal val LocalPageImageLoadingAllowed = staticCompositionLocalOf { true }

/**
 * Removing AsyncImage cancels its request and forgets its animated painter. Keep only the
 * decoded result for a static replay; do not change the request/cache identity on resume.
 */
@Composable
internal fun PageAwareAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = model as? ImageRequest
    val imageKey = request?.data ?: model
    var retainedImage by remember(imageKey, request?.memoryCacheKey, request?.memoryCacheKeyExtras, imageLoader) {
        mutableStateOf<coil3.Image?>(null)
    }
    val retainedPainter = remember(retainedImage, context) {
        retainedImage?.asPainter(context, FilterQuality.Low)
    }
    if (LocalPageImageLoadingAllowed.current) {
        AsyncImage(
            model = model,
            imageLoader = imageLoader ?: context.imageLoader,
            contentDescription = contentDescription,
            modifier = modifier,
            placeholder = retainedPainter,
            contentScale = contentScale,
            alignment = alignment,
            onSuccess = {
                retainedImage = it.result.image
                onSuccess?.invoke(it)
            },
        )
    } else {
        // AsyncImage's forgotten callback runs during apply. Restore drawable visibility
        // afterwards for static drawing, without restarting its frame clock.
        SideEffect {
            (retainedImage as? DrawableImage)?.drawable?.let { drawable ->
                drawable.setVisible(true, false)
                (drawable as? Animatable)?.stop()
            }
        }
        if (retainedPainter != null) {
            Image(
                painter = retainedPainter,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                alignment = alignment,
            )
        } else {
            Box(modifier.semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            })
        }
    }
}
