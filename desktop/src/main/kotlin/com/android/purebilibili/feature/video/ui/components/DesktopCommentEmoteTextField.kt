package com.android.purebilibili.feature.video.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import okio.buffer

/** Windows boundary for the original EditText/ImageSpan: successful original Coil
 * images decorate raw codes in a native editable document, using the actual owner. */
@Composable internal fun CommentEmoteTextField(
    value:TextFieldValue,onValueChange:(TextFieldValue)->Unit,emoteUrls:Map<String,String>,
    enabled:Boolean,readOnly:Boolean,hint:String,textStyle:TextStyle,hintColor:Color,cursorColor:Color,
    onBeginEditing:()->Unit,modifier:Modifier=Modifier,
) {
    val platform=LocalDesktopCommentBindings.current
    val images=LocalDesktopApplicationImageLoader.current.imageLoader
    val parent=rememberCoroutineScope()
    val scope=remember(platform,images){CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job]))}
    val change by rememberUpdatedState(onValueChange);val begin by rememberUpdatedState(onBeginEditing)
    val density=LocalDensity.current
    val textPx=with(density){textStyle.fontSize.toPx()}.takeIf{it.isFinite()&&it>0}?:14f
    val size=with(density){22.dp.roundToPx()}.coerceAtLeast(1)
    val padding=with(density){2.dp.roundToPx()}.coerceAtLeast(0)
    val editor=remember(platform,images){DesktopInlineEmotePane(EMOTE_TOKEN_PATTERN,platform::isOwned)}
    DisposableEffect(editor,scope) {onDispose{scope.cancel();if(SwingUtilities.isEventDispatchThread())editor.retire()else SwingUtilities.invokeLater(editor::retire)}}
    val requests=remember(editor){mutableMapOf<String,Job>()}
    SwingPanel(factory={
        JScrollPane(editor).apply {
            isOpaque=false;viewport.isOpaque=false;border=null
            horizontalScrollBarPolicy=JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy=JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize=Dimension(1,54)
        }
    },modifier=modifier.semantics {
        editableText=AnnotatedString(value.text);textSelectionRange=value.selection
        if(!enabled)disabled()
        setText {replacement->if(!enabled||readOnly||!platform.isOwned())false else {editor.replaceAllRaw(replacement.text);true}}
        insertTextAtCursor {replacement->if(!enabled||readOnly||!platform.isOwned())false else {editor.replaceSelection(replacement.text);true}}
        setSelection {start,end,_->if(start !in 0..value.text.length||end !in 0..value.text.length)false else {editor.caret.setDot(start);editor.caret.moveDot(end);true}}
    },update={
        editor.valueChanged={if(platform.isOwned())change(it)}
        editor.beginEditing={if(platform.isOwned())begin()}
        editor.font=Font("Dialog",if((textStyle.fontWeight?.weight?:400)>=600)Font.BOLD else Font.PLAIN,textPx.toInt().coerceAtLeast(1))
        editor.foreground=java.awt.Color(textStyle.color.toArgb(),true)
        editor.caretColor=java.awt.Color(cursorColor.toArgb(),true)
        editor.selectionColor=java.awt.Color(cursorColor.copy(alpha=.3f).toArgb(),true)
        editor.hint=hint;editor.hintColor=java.awt.Color(hintColor.toArgb(),true)
        editor.requestImage={url->
            if(platform.isOwned()&&scope.isActive&&!requests.containsKey(url)) {
                val job=scope.launch(start=CoroutineStart.LAZY) {
                    val normalized=when{url.startsWith("//")->"https:$url";url.startsWith("http://")->"https:${url.removePrefix("http:")}";else->url}
                    try {
                        ensureActive();if(!platform.isOwned())return@launch
                        val result=images.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(normalized).size(size).build()) as? SuccessResult?:return@launch
                        ensureActive();if(!platform.isOwned())return@launch
                        // Only the temporary raster belongs to this view. Coil retains
                        // ownership of its decoded result and memory-cache bitmap.
                        val bitmap=org.jetbrains.skia.Bitmap()
                        val raster=try {
                            check(bitmap.allocN32Pixels(size,size))
                            org.jetbrains.skia.Canvas(bitmap).use {canvas->
                                canvas.clear(0);canvas.scale(size.toFloat()/result.image.width.coerceAtLeast(1),size.toFloat()/result.image.height.coerceAtLeast(1))
                                result.image.draw(canvas)
                            }
                            BufferedImage(size+padding*2,size,BufferedImage.TYPE_INT_ARGB).also {image->
                                for(y in 0 until size)for(x in 0 until size)image.setRGB(x+padding,y,bitmap.getColor(x,y))
                            }
                        }finally{bitmap.close()}
                        ensureActive()
                        val animation=try {
                            val cache=images.diskCache
                            val key=result.diskCacheKey
                            if(cache==null||key==null)null else cache.openSnapshot(key)?.use {snapshot->
                                val encoded=withContext(Dispatchers.IO) {cache.fileSystem.source(snapshot.data).buffer().use {source->source.inputStream().readNBytes(2*1024*1024+1)}}
                                ensureActive()
                                if(!platform.isOwned()||encoded.size>2*1024*1024)null
                                else com.bilipai.desktop.plugins.DesktopAnimatedSkinImage.decodeOrNull(encoded)
                            }
                        }catch(cancelled:CancellationException){throw cancelled}
                        catch(_:Exception){null}
                        // Retire a decoded lease even when cancellation/epoch replacement
                        // occurs between finishing the cache read and EDT publication.
                        if(!scope.isActive||!platform.isOwned()){animation?.close();return@launch}
                        val icon=if(animation==null)ImageIcon(raster)else try{DesktopInlineAnimatedEmoteIcon(animation,size,padding)}catch(failure:Throwable){animation.close();throw failure}
                        editor.imageReady(url,icon)
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception) { /* Same original text fallback, no synthetic success. */ }
                }
                requests[url]=job;job.start()
            }
        }
        editor.bind(value,emoteUrls,enabled,readOnly)
    })
}
