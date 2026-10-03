@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.android.bilipai.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.android.bilipai.tv.QrPhase
import com.android.bilipai.tv.TvUiState
import com.android.purebilibili.data.model.VideoQuality

@Composable
internal fun TvSearchInput(state: TvUiState, requester: FocusRequester, onSearch: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(state.query) }
    val submitFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() { if (draft.isNotBlank()) { keyboard?.hide(); onSearch(draft) } }
    LaunchedEffect(requester) { requester.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f).focusRequester(requester).testTag("tv-search-input")
                    .background(Color(0xFF242C3C), RoundedCornerShape(12.dp)).padding(18.dp)
                    .onPreviewKeyEvent { event ->
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER -> {
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) keyboard?.show()
                                true
                            }
                            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) submit()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    keyboard?.hide()
                                    submitFocus.requestFocus()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { field ->
                    if (draft.isEmpty()) Text("输入视频关键词", color = Color(0xFFABB3C5))
                    field()
                })
            Button(onClick = { submit() }, modifier = Modifier.focusRequester(submitFocus)) { Text("搜索") }
        }
        val suggestions = (state.searchHistory.take(3) + state.trending.take(3)).distinct().take(5)
        if (suggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.forEach { word ->
                    Button(onClick = { draft = word; submit() }, modifier = Modifier.weight(1f)) {
                        Text(word, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvDetailContent(state: TvUiState, requester: FocusRequester,
    onPlay: (Long) -> Unit, onWatchLater: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    if (state.detailLoading) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("正在加载视频详情…")
            FocusButton("返回列表", onBack, requester)
        }
        return
    }
    val info = state.detail
    if (info == null) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(state.detailError ?: "未找到视频")
            FocusButton("重试", onRetry, requester)
        }
        return
    }
    LaunchedEffect(requester, info.bvid) { requester.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = TvUiTokens.pagePadding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            AsyncImage(model = info.pic, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(320.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(info.title, style = MaterialTheme.typography.headlineMedium)
                Text(info.owner.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { onPlay(info.cid) }, modifier = Modifier.focusRequester(requester).testTag("tv-play")) { Text("播放 / 继续观看") }
                    Button(onClick = onWatchLater) { Text("稍后再看") }
                }
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Text(info.desc.ifBlank { "暂无简介" }, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (info.pages.size > 1) {
            Text("选集", style = MaterialTheme.typography.titleLarge)
            info.pages.forEach { page ->
                Button(onClick = { onPlay(page.cid) }, modifier = Modifier.fillMaxWidth()) { Text("P${page.page} · ${page.part}") }
            }
        }
    }
}

@Composable
internal fun TvLoginContent(state: TvUiState, requester: FocusRequester, onRefresh: () -> Unit, onSignOut: () -> Unit) {
    LaunchedEffect(requester, state.qr.phase == QrPhase.Success || state.account != null) { requester.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = TvUiTokens.pagePadding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("账号", style = MaterialTheme.typography.headlineLarge)
        if (state.account != null || state.qr.phase == QrPhase.Success) {
            Text("已登录 · ${state.account?.uname ?: "正在读取账号信息"}", style = MaterialTheme.typography.titleLarge)
            Text("收藏、历史和稍后再看与当前账号同步。")
            state.accountError?.let { Text(it) }
            Button(onClick = onSignOut, modifier = Modifier.focusRequester(requester)) { Text("退出登录") }
        } else {
            Text("使用哔哩哔哩手机 App 扫码，并在手机上确认登录。", style = MaterialTheme.typography.bodyLarge)
            state.qr.bitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(), contentDescription = "扫码登录二维码",
                    modifier = Modifier.size(250.dp).background(Color.White).padding(8.dp))
            }
            Text(when (state.qr.phase) {
                QrPhase.Loading -> "正在生成二维码…"
                QrPhase.Waiting -> "等待扫码"
                QrPhase.Scanned -> "已扫码，请在手机上确认"
                QrPhase.Expired -> "二维码已过期，请刷新"
                QrPhase.Failed -> state.qr.error ?: "登录失败，请重试"
                QrPhase.Success -> "登录成功"
            })
            Button(onClick = onRefresh, modifier = Modifier.focusRequester(requester)) { Text("刷新二维码") }
            Text("返回即可取消本次登录。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TvSettingsContent(state: TvUiState, requester: FocusRequester, onQuality: (Int) -> Unit,
    onAutoContinue: () -> Unit, onDanmaku: () -> Unit, onPrivacy: () -> Unit, onClearSearchHistory: () -> Unit) {
    var chooseQuality by remember { mutableStateOf(false) }
    LaunchedEffect(requester, chooseQuality) { if (!chooseQuality) requester.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = TvUiTokens.pagePadding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("播放与隐私", style = MaterialTheme.typography.headlineLarge)
        Button(onClick = { chooseQuality = true }, modifier = Modifier.focusRequester(requester)) {
            Text("默认画质：${VideoQuality.fromCode(state.quality)?.description ?: state.quality}")
        }
        Button(onClick = onAutoContinue) { Text("播完自动播放下一 P：${if (state.autoContinue) "开启" else "关闭"}") }
        Button(onClick = onDanmaku) { Text("弹幕显示：${if (state.danmakuEnabled) "开启" else "关闭"}") }
        Button(onClick = onPrivacy) { Text("暂停上报观看历史：${if (state.privacyMode) "开启" else "关闭"}") }
        Button(onClick = onClearSearchHistory) { Text("清空搜索历史") }
        Spacer(Modifier.height(8.dp))
        Text("画质可用性由账号权限、视频内容和设备能力决定。")
        Text("界面动效遵循系统“减少动画”设置；首页背景取自轮播封面。")
    }
    if (chooseQuality) TvChoiceDialog("默认画质", listOf(32, 64, 80, 112, 116, 120).map {
        it to (VideoQuality.fromCode(it)?.description ?: "$it")
    }, onDismiss = { chooseQuality = false }, onChoose = { onQuality(it); chooseQuality = false })
}

@Composable
internal fun <T> TvChoiceDialog(title: String, options: List<Pair<T, String>>, onDismiss: () -> Unit, onChoose: (T) -> Unit) {
    val requester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(520.dp).background(Color(0xFF1D2535), RoundedCornerShape(20.dp))
            .padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            options.forEachIndexed { index, (value, label) ->
                Button(onClick = { onChoose(value) }, modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(requester) else Modifier)) { Text(label) }
            }
            Button(onClick = onDismiss, modifier = if (options.isEmpty()) Modifier.focusRequester(requester) else Modifier) { Text("取消") }
        }
        LaunchedEffect(requester) { requester.requestFocus() }
    }
}
