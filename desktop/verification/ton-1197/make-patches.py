"""Maintainer helper: derive strict diffs from the normal producer output; never copy a renderer."""
from pathlib import Path
import difflib
root = Path(__file__).resolve().parents[2]
package = 'com/android/purebilibili/feature/dynamic/components/'
def patch(name, adapt, migrated=False):
    relative = ('app/src/main/java/' + package + name) if migrated else ('dynamic-full-card/' + package + name)
    original = ((root.parent/relative) if migrated else (root/'build/generated'/relative)).read_text()
    updated = adapt(original)
    lane = 'patches' if migrated else 'transition/patches'
    target = root/'upstream-core'/lane/(relative+'.patch')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(''.join(difflib.unified_diff(original.splitlines(True), updated.splitlines(True), fromfile='a/'+relative, tofile='b/'+relative)))
def replace(s, a, b):
    assert s.count(a) == 1, a
    return s.replace(a, b)
def renderer(s):
    s=replace(s, 'import androidx.compose.foundation.background\n', '''import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
''')
    s=replace(s, '''            LaunchedEffect(dismissRequestCount) {
                if (dismissRequestCount > 0) triggerDismiss()
            }''', '''            fun dismissTopLayer() {
                when {
                    showOrdinaryImageActions -> showOrdinaryImageActions = false
                    showLivePhotoMenu -> showLivePhotoMenu = false
                    else -> triggerDismiss()
                }
            }
            LaunchedEffect(dismissRequestCount) {
                if (dismissRequestCount > 0) dismissTopLayer()
            }''')
    s=replace(s, '''            // 1. 背景层 (淡入淡出)
''', '''            val keyboardFocus = remember { FocusRequester() }
            var viewerFocused by remember { mutableStateOf(false) }
            LaunchedEffect(requestToken, showOrdinaryImageActions) {
                if (!showOrdinaryImageActions) keyboardFocus.requestFocus()
            }
            Box(Modifier.fillMaxSize()
                .testTag("desktop-image-viewer")
                .semantics { stateDescription = "${pagerState.currentPage + 1}/${images.size}" }
                .focusRequester(keyboardFocus)
                .onFocusChanged { viewerFocused = it.isFocused }
                // Bubble after children: text editors and nested menus own their keys first.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when {
                        event.key == Key.Escape -> { dismissTopLayer(); true }
                        viewerFocused && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) -> {
                            if (!isDismissing && !showOrdinaryImageActions && !showLivePhotoMenu) {
                                val step = if (event.key == Key.DirectionRight) 1 else -1
                                scope.launch {
                                    pagerState.scrollToPage((pagerState.currentPage + step).coerceIn(0, images.lastIndex))
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()) {
            // 1. 背景层 (淡入淡出)
''')
    s=replace(s, '''            }
    }

    if (showOrdinaryImageActions) {''', '''            }
            } // keyboard scope; original pager/chrome remain in the same host
    }

    if (showOrdinaryImageActions) {''')
    s=replace(s, '''            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ImagePreviewActionButton(''', '''            text = {
                // Miuix creates its window content later; request focus only once attached.
                val actionsFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { actionsFocus.requestFocus() }
                Column(modifier = Modifier.fillMaxWidth()
                    .testTag("desktop-image-actions")
                    .focusRequester(actionsFocus).onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            showOrdinaryImageActions = false
                            true
                        } else false
                    }.focusable()) {
                    ImagePreviewActionButton(''')
    return s
def grid(s):
    s=replace(s, 'import androidx.compose.runtime.Composable\n', '''import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
''')
    s=replace(s, '''    val sourceHidden = isImagePreviewSourceHidden(imageRectRef.value, imageUrl)
''', '''    val sourceHidden = isImagePreviewSourceHidden(imageRectRef.value, imageUrl)
    val previewOpen = isImagePreviewOpen()
    val sourceFocus = remember(imageUrl) { FocusRequester() }
    var returnFocus by remember(imageUrl) { mutableStateOf(false) }
    LaunchedEffect(previewOpen, imageUrl) {
        if (!previewOpen && returnFocus) {
            // The source's clickable was disabled while hidden. Wait for reattachment.
            withFrameNanos { }
            sourceFocus.requestFocus()
            returnFocus = false
        }
    }
''')
    s=replace(s, '''        modifier = modifier
            .alpha''', '''        modifier = modifier
            .testTag("dynamic-image-$index")
            .focusRequester(sourceFocus)
            .alpha''')
    s=replace(s, '''                val rect = imageRectRef.value
''', '''                returnFocus = true
                val rect = imageRectRef.value
''')
    return s
patch('DesktopOriginalImagePreviewRenderer.kt', renderer)
patch('DrawGrid.kt', grid, migrated=True)
