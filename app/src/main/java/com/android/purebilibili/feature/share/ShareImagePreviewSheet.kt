package com.android.purebilibili.feature.share

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.util.rememberStoragePermissionState
import com.android.purebilibili.feature.screenshot.saveAppScreenshotBitmapToGallery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareImagePreviewSheet(document: ShareImageDocument, onDismiss: () -> Unit, modifier: Modifier = Modifier, capturedFiles: List<File> = emptyList(), loadDocument: (suspend () -> ShareImageDocument)? = null) {
    val context = LocalContext.current
    val isDarkTheme = com.android.purebilibili.core.ui.isAppInDarkTheme()
    val scope = rememberCoroutineScope()
    var footer by remember(document.key) { mutableStateOf(true) }
    var files by remember(document.key) { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember(document.key) { mutableStateOf(true) }
    var busy by remember(document.key) { mutableStateOf(false) }
    var error by remember(document.key) { mutableStateOf<String?>(null) }
    var status by remember(document.key) { mutableStateOf<String?>(null) }
    val savedFiles = remember(document.key) { mutableSetOf<String>() }
    var retry by remember(document.key) { mutableIntStateOf(0) }
    suspend fun save() {
        if (busy || loading || files.isEmpty()) return
        busy = true
        error = null
        try {
            files.forEach { file ->
                if (file.absolutePath in savedFiles) return@forEach
                val saved = withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: kotlin.error("读取图片失败")
                    try { saveAppScreenshotBitmapToGallery(context, bitmap, "BiliPai_${file.name}") } finally { bitmap.recycle() }
                }
                check(saved) { "已保存 ${savedFiles.size}/${files.size} 张，其余保存失败，请重试" }
                savedFiles += file.absolutePath
            }
            status = "已保存 ${files.size} 张图片到相册"
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "保存失败" } finally { busy = false }
    }
    val permission = rememberStoragePermissionState { granted -> if (granted) scope.launch { save() } }
    LaunchedEffect(document, footer, retry, isDarkTheme) {
        loading = true
        files = emptyList()
        error = null
        status = null
        savedFiles.clear()
        try { files = capturedFiles.ifEmpty { renderShareImages(context, loadDocument?.invoke() ?: document, footer, isDarkTheme) } } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "生成图片失败，请重试" } finally { loading = false }
    }
    AppModalBottomSheet(modifier = modifier, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("${document.title}图片预览")
            if (loading) AppText("正在加载完整内容并生成图片…")
            if (files.size > 1) AppText("内容较长，已分为 ${files.size} 张图片，保存和分享均包含全部内容")
            AppLazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(files, key = { it.absolutePath }) { file ->
                    val bounds = remember(file) { BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(file.absolutePath, this) } }
                    AsyncImage(file, null, modifier = Modifier.fillMaxWidth().aspectRatio(bounds.outWidth.toFloat() / bounds.outHeight.coerceAtLeast(1)))
                }
            }
            if (capturedFiles.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                AppCheckbox(checked = footer, onCheckedChange = { if (!busy && !loading) footer = it })
                AppText("显示二维码和署名页脚")
            }
            error?.let { AppText(it); AppTextButton(onClick = { retry++ }, enabled = !loading && !busy) { AppText("重新生成") } }
            status?.let { AppText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { AppText("关闭") }
                AppButton(onClick = {
                    try { shareImageFiles(context, files) } catch (_: Exception) { error = "无法打开图片分享，请重试" }
                }, enabled = !loading && !busy && files.isNotEmpty(), modifier = Modifier.weight(1f)) { AppText("分享") }
                AppButton(onClick = { if (permission.isGranted) scope.launch { save() } else permission.request() }, enabled = !loading && !busy && files.isNotEmpty(), modifier = Modifier.weight(1f)) { AppText(if (busy) "保存中…" else "保存") }
            }
        }
    }
}
