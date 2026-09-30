package com.bilipai.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.store.DEFAULT_PLAYBACK_SPEED_OPTIONS
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.update.DesktopUpdater
import com.bilipai.desktop.update.UpdateState
import com.bilipai.desktop.update.WindowsUpdate
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.net.URI

private enum class Section(val label: String, val symbol: String) {
    HOME("推荐", "⌂"), POPULAR("热门", "◉"), HISTORY("观看历史", "◷"), FAVORITES("本地收藏", "♡"), SEARCH("搜索", "⌕")
}
private val accent = Color(0xFF256D77)

private fun VideoDetails.toCard() = VideoCard(bvid, title, cover, author, playCount, pages.firstOrNull()?.duration?.toInt() ?: 0)
private fun formatCount(count: Long) = if (count >= 10000) "%.1f万".format(count / 10000.0) else count.toString()
private fun time(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%02d:%02d".format(total / 60, total % 60)
}

@Composable
internal fun FeedCard(card: VideoCard, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Box {
                AsyncImage(card.cover, card.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant))
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.48f)).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("▷ ${formatCount(card.playCount)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(time(card.duration.toDouble()), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(card.author, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun LoginDialog(repository: DesktopRepository, account: AccountSummary?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var qr by remember { mutableStateOf<QrLogin?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var error by remember { mutableStateOf<String?>(null) }
    var cookie by remember { mutableStateOf("") }
    var cookieMode by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(generation, cookieMode) {
        if (account != null || cookieMode) return@LaunchedEffect
        try {
            qr = repository.beginQrLogin()
            while (true) {
                delay(1500)
                when (val result = repository.pollQrLogin(qr!!.key)) {
                    QrLoginState.Waiting -> status = "请使用哔哩哔哩手机客户端扫码"
                    QrLoginState.Scanned -> status = "已扫码，请在手机上确认登录"
                    QrLoginState.Expired -> { status = "二维码已过期，请刷新"; break }
                    is QrLoginState.Complete -> { onDismiss(); break }
                }
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            error = exception.message ?: "获取二维码失败"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (account != null) "账号" else "登录 BiliPai") },
        text = {
            Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (account != null) {
                    Text(account.name, style = MaterialTheme.typography.titleLarge)
                    Text("UID ${account.mid}")
                    Text("登录信息保存在这台电脑。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (cookieMode) {
                    Text("粘贴你自己的 B 站 Cookie，包含 SESSDATA。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = cookie, onValueChange = { cookie = it }, label = { Text("Cookie") },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), minLines = 3)
                } else {
                    qr?.let { login ->
                        val bitmap = remember(login.url) {
                            val matrix = MultiFormatWriter().encode(login.url, BarcodeFormat.QR_CODE, 256, 256)
                            BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).apply {
                                for (y in 0 until 256) for (x in 0 until 256) setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
                            }.toComposeImageBitmap()
                        }
                        Image(BitmapPainter(bitmap), "登录二维码", Modifier.size(256.dp))
                    }
                    Text(status)
                    TextButton(onClick = { error = null; qr = null; generation++ }) { Text("刷新二维码") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            if (account != null) TextButton(onClick = { repository.logout(); onDismiss() }) { Text("退出登录") }
            else if (cookieMode) TextButton(onClick = {
                scope.launch { try { repository.importCookies(cookie); onDismiss() }
                    catch (exception: Exception) { error = exception.message ?: "Cookie 登录失败" } }
            }, enabled = cookie.isNotBlank()) { Text("登录") }
            else TextButton(onClick = onDismiss) { Text("完成") }
        }, dismissButton = {
            if (account == null) TextButton(onClick = { cookieMode = !cookieMode; error = null }) {
                Text(if (cookieMode) "扫码登录" else "Cookie 登录")
            }
        })
}
