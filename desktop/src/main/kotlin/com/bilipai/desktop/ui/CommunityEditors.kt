package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.note.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

internal data class CommunityLocalImage(val path: Path, val mime: String) {
    val name: String get() = path.fileName.toString()
    fun bytes(): ByteArray {
        require(Files.size(path) in 1..(15L * 1024 * 1024)) { "图片为空或超过 15MB" }
        return Files.readAllBytes(path).also { validateCommunityImage(name, mime, it) }
    }
}

internal suspend fun selectCommunityImages(multiple: Boolean): List<CommunityLocalImage> = withContext(Dispatchers.IO) {
    var result = emptyList<CommunityLocalImage>()
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择图片"; isMultiSelectionEnabled = multiple
            fileFilter = FileNameExtensionFilter("图片 JPG / PNG / GIF / WebP", "jpg", "jpeg", "png", "gif", "webp")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = (if (multiple) chooser.selectedFiles.toList() else listOf(chooser.selectedFile)).map { file ->
                val mime = when (file.extension.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
                    else -> error("请选择支持的图片格式")
                }
                CommunityLocalImage(file.toPath(), mime)
            }
        }
    }
    result
}

@Composable
internal fun CommunityNoteEditor(video: VideoDetails, document: VideoNoteEditorDocument, noteId: String?,
    community: DesktopCommunityRepository, onLogin: () -> Unit, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember(document) { mutableStateOf(document.title.ifBlank { video.title }) }
    var blocks by remember(document) { mutableStateOf(document.blocks.ifEmpty { listOf(VideoNoteBlock.Text("")) }) }
    var publish by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<Throwable?>(null) }
    var part by remember { mutableIntStateOf(0) }; var seconds by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    val edited = VideoNoteEditorDocument(title, blocks)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (noteId == null) "新建视频笔记" else "编辑我的笔记") }, text = {
        Column(Modifier.width(720.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, enabled = !busy, label = { Text("笔记标题") }, modifier = Modifier.fillMaxWidth())
            blocks.forEachIndexed { index, block -> key(index) {
                when (block) {
                    is VideoNoteBlock.Text -> {
                        OutlinedTextField(block.text, { value -> blocks = blocks.toMutableList().apply { set(index, block.copy(text = value)) } },
                            enabled = !busy, minLines = 2, label = { Text("文字段落") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(block.bold, { blocks = blocks.toMutableList().apply { set(index, block.copy(bold = !block.bold)) } }, label = { Text("粗体") }, enabled = !busy)
                            FilterChip(block.highlight, { blocks = blocks.toMutableList().apply { set(index, block.copy(highlight = !block.highlight)) } }, label = { Text("高亮") }, enabled = !busy)
                            FilterChip(block.unorderedList, { blocks = blocks.toMutableList().apply { set(index, block.copy(unorderedList = !block.unorderedList)) } }, label = { Text("列表") }, enabled = !busy)
                        }
                    }
                    is VideoNoteBlock.Quote -> OutlinedTextField(block.text, { value ->
                        blocks = blocks.toMutableList().apply { set(index, block.copy(text = value)) }
                    }, enabled = !busy, minLines = 2, label = { Text("引用段落") }, modifier = Modifier.fillMaxWidth())
                    is VideoNoteBlock.Timestamp -> Text("▶ ${block.label} · 分P ${block.index + 1}")
                }
                TextButton(enabled = !busy, onClick = { blocks = blocks.filterIndexed { i, _ -> i != index } }) { Text("删除此段") }
            } }
            TextButton(enabled = !busy, onClick = { blocks = blocks + VideoNoteBlock.Text("\n") }) { Text("添加文字段落") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && video.pages.isNotEmpty(), onClick = { part = (part + 1) % video.pages.size }) { Text("分P ${part + 1}") }
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit) }, enabled = !busy, label = { Text("时间（秒）") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy && seconds.toLongOrNull() != null && video.pages.isNotEmpty(), onClick = {
                    blocks = blocks + VideoNoteBlock.Timestamp(seconds.toLong(), video.pages[part].cid, part, video.pages.size); seconds = ""
                }) { Text("插入时间戳") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(publish, { publish = it }, enabled = !busy); Text("公开发布（由平台审核）") }
            TextButton(enabled = !busy, onClick = {
                try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(buildVideoNoteShareText(video.title, video.bvid, edited)), null); feedback = "分享文字已复制" }
                catch (failure: Exception) { error = failure }
            }) { Text("复制笔记分享内容") }
            if (noteId != null) TextButton(enabled = !busy, onClick = { deleting = true }) { Text("删除这篇笔记") }
            if (feedback.isNotBlank()) Text(feedback)
            error?.let { CommunityFailure(it, onLogin) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(enabled = !busy && VideoNoteContentCodec.toPlainText(edited).isNotBlank(), onClick = {
            busy = true; error = null; scope.launch {
                try { community.savePrivateNote(video.aid, edited, noteId, publish); onSaved() }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { busy = false }
            }
        }) { Text(if (publish) "保存并申请公开" else "保存私人笔记") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
    if (deleting) AlertDialog(onDismissRequest = { if (!busy) deleting = false }, title = { Text("删除这篇云端笔记？") },
        text = { Text("删除后无法在笔记列表恢复。") }, confirmButton = {
            CommunityAction("确认删除", onLogin, action = { community.deletePrivateNote(video.aid, noteId!!) }, onSuccess = { deleting = false; onSaved() })
        }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
internal fun CommunityCommentActions(target: CommunityCommentTarget, comment: Comment,
    community: DesktopCommunityRepository, onLogin: () -> Unit, onChanged: () -> Unit) {
    val account by community.account.collectAsState()
    var deleting by remember(comment.id) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CommunityAction(if (comment.liked) "取消赞 (${comment.likeCount})" else "赞 (${comment.likeCount})", onLogin,
            action = { community.setCommentLike(target, comment.id, !comment.liked) }, onSuccess = onChanged)
        if (comment.memberId > 0 && account?.mid != comment.memberId) DesktopBlockedUpAction(
            community.blockedUpRepository, comment.memberId, comment.author, comment.avatar, onLogin,
            source = com.android.purebilibili.data.repository.BlockedUpRelationSource.COMMENT)
        if (account?.mid == comment.memberId && comment.memberId > 0) TextButton(onClick = { deleting = true }) { Text("删除我的评论") }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这条评论？") },
        text = { Text(comment.text) }, confirmButton = {
            CommunityAction("确认删除", onLogin, action = { community.deleteComment(target, comment.id) }, onSuccess = { deleting = false; onChanged() })
        }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}
