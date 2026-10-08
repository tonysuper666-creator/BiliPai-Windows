package com.bilipai.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.CaptchaData
import com.android.purebilibili.feature.login.*
import com.bilipai.desktop.data.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import java.awt.image.BufferedImage

private enum class LoginMethod(val label: String) { TV("TV 扫码"), WEB("网页扫码"), PASSWORD("密码"), SMS("短信"), COOKIE("Cookie"), ACCOUNTS("账号") }

@Composable
internal fun AdvancedLoginDialog(repository: DesktopRepository, updateHold: DesktopLoginUpdateHold,
    onDismiss: () -> Unit, onComplete: (AccountSummary) -> Unit,
    loginReturn: DesktopLoginReturnBinding? = null) {
    if (!updateHold.canBegin()) return
    val instance = remember(updateHold) { updateHold.newInstance() }
    var admitted by remember(instance) { mutableStateOf(false) }
    DisposableEffect(instance) {
        admitted = instance.mount()
        onDispose { admitted = false; instance.close() }
    }
    if (!admitted) return
    val account by repository.account.collectAsState()
    var method by remember { mutableStateOf(if (account == null) LoginMethod.TV else LoginMethod.ACCOUNTS) }
    val attempt = remember(instance) { java.util.concurrent.atomic.AtomicReference(Any()) }
    var attemptVersion by remember(instance) { mutableIntStateOf(0) }
    fun replaceAttempt() { attempt.set(Any()); attemptVersion++ }
    val capturedAttempt = remember(instance, attemptVersion) { attempt.get() }
    val ownsAttempt = remember(instance, loginReturn, capturedAttempt) { {
        !instance.isRetired() && attempt.get() === capturedAttempt && (loginReturn?.owns?.invoke() ?: true)
    } }
    val installed = remember(loginReturn, capturedAttempt) { loginReturn?.let { binding ->
        { receipt: DesktopLoginInstallationReceipt ->
            // Explicit refresh/change/cancel retires intent. Account epoch's automatic dispose must not
            // discard a success already committed by the original IO installer before its EDT return.
            if (attempt.get() === capturedAttempt) binding.installed(receipt)
        }
    } }
    val login = remember(repository, instance, loginReturn, capturedAttempt) {
        if (loginReturn == null) DesktopLoginRepository(repository)
        else DesktopLoginRepository(repository, loginReturn.sourceEpoch, ownsAttempt, installed)
    }
    val bridge = remember(login) { DesktopCaptchaBridge(login) }
    CompositionLocalProvider(LocalDesktopLoginUpdateInstance provides instance,
        LocalDesktopLoginReturn provides loginReturn, LocalDesktopLoginAttemptOwned provides ownsAttempt,
        LocalDesktopLoginInstalled provides installed, LocalDesktopLoginAttemptReset provides { replaceAttempt() }) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("账号与登录") }, text = {
        Column(Modifier.width(650.dp).heightIn(max = 670.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LoginMethod.entries.forEach { value -> FilterChip(method == value, { if (method != value) { replaceAttempt(); method = value } }, label = { Text(value.label) }) }
            }
            key(method) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (method) {
                        LoginMethod.TV, LoginMethod.WEB -> LoginQrForm(login, method == LoginMethod.TV, onComplete)
                        LoginMethod.PASSWORD -> LoginPasswordForm(login, bridge, onComplete)
                        LoginMethod.SMS -> LoginSmsForm(login, bridge, onComplete)
                        LoginMethod.COOKIE -> LoginCookieForm(repository, onComplete)
                        LoginMethod.ACCOUNTS -> LoginAccountsForm(repository, login, onComplete, onDismiss)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    }
}

private val LocalDesktopLoginUpdateInstance = staticCompositionLocalOf<DesktopLoginUpdateHold.Instance> {
    error("Login form requires its mounted Main instance")
}

private val LocalDesktopLoginReturn = staticCompositionLocalOf<DesktopLoginReturnBinding?> { null }
private val LocalDesktopLoginAttemptOwned = staticCompositionLocalOf<() -> Boolean> { { true } }
private val LocalDesktopLoginInstalled = staticCompositionLocalOf<((DesktopLoginInstallationReceipt) -> Unit)?> { null }
private val LocalDesktopLoginAttemptReset = staticCompositionLocalOf<() -> Unit> { {} }

internal class LoginWork(private val scope: CoroutineScope, private val instance: DesktopLoginUpdateHold.Instance) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null)
    fun run(block: suspend () -> Unit): Job? {
        if (busy) return null
        busy = true; error = null; notice = null
        return launchTrackedLogin(scope, instance, onRejected = { busy = false }) {
            try { block() }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "登录操作失败" }
            finally { busy = false }
        }
    }
}

@Composable private fun rememberLoginWork(): LoginWork {
    val scope = rememberCoroutineScope()
    val instance = LocalDesktopLoginUpdateInstance.current
    return remember(scope, instance) { LoginWork(scope, instance) }
}
@Composable private fun LoginWorkState(work: LoginWork) {
    if (work.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("处理中…") }
    work.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    work.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun LoginQrForm(login: DesktopLoginRepository, tv: Boolean, onComplete: (AccountSummary) -> Unit) {
    var qr by remember { mutableStateOf<QrLogin?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var error by remember { mutableStateOf<String?>(null) }
    val complete by rememberUpdatedState(onComplete)
    val scope = rememberCoroutineScope()
    val instance = LocalDesktopLoginUpdateInstance.current
    val resetAttempt = LocalDesktopLoginAttemptReset.current
    DisposableEffect(scope, instance, generation, login) {
        val job = launchTrackedLogin(scope, instance) {
        qr = null; error = null; status = "正在获取二维码…"
        try {
            val current = if (tv) login.beginTvQr() else login.beginWebQr()
            qr = current; status = "请使用哔哩哔哩手机客户端扫码"
            val deadline = System.currentTimeMillis() + 8 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                when (val result = if (tv) login.pollTvQr(current.key) else login.pollWebQr(current.key)) {
                    QrLoginState.Waiting -> status = "请使用哔哩哔哩手机客户端扫码"
                    QrLoginState.Scanned -> status = "已扫码，请在手机上确认登录"
                    QrLoginState.Expired -> { status = "二维码已过期，请刷新"; return@launchTrackedLogin }
                    is QrLoginState.Complete -> { complete(result.account); return@launchTrackedLogin }
                }
            }
            status = "二维码已过期，请刷新"
        } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "获取二维码失败" }
        }
        onDispose { job.cancel() }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        qr?.let { value ->
            val image = remember(value.url) {
                val matrix = MultiFormatWriter().encode(value.url, BarcodeFormat.QR_CODE, 256, 256)
                BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).apply {
                    for (y in 0 until 256) for (x in 0 until 256) setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
                }.toComposeImageBitmap()
            }
            Image(BitmapPainter(image), "登录二维码", Modifier.size(256.dp))
        } ?: CircularProgressIndicator()
        Text(status)
        Text(if (tv) "TV 授权同时保存 App access token，可用于上游 App 接口。" else "网页扫码保存浏览器 Cookie。", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { resetAttempt(); generation++ }) { Text("刷新二维码") }
    }
}

@Composable
private fun LoginPasswordForm(login: DesktopLoginRepository, bridge: DesktopCaptchaBridge, onComplete: (AccountSummary) -> Unit) {
    val work = rememberLoginWork()
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf<CaptchaData?>(null) }
    var risk by remember { mutableStateOf<DesktopLoginResult.RiskRequired?>(null) }
    fun accept(result: DesktopLoginResult) { when (result) {
        is DesktopLoginResult.Complete -> { password = ""; onComplete(result.account) }
        is DesktopLoginResult.CaptchaRequired -> { captcha = result.captcha; risk = null; work.notice = "请完成安全验证后继续登录" }
        is DesktopLoginResult.RiskRequired -> { risk = result; captcha = null }
    } }
    if (risk != null) LoginRiskForm(risk!!, login, bridge, ::accept) { risk = null }
    else {
        OutlinedTextField(username, { username = it; captcha = null }, label = { Text("手机号 / 邮箱 / 用户名") }, singleLine = true, enabled = !work.busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it; captcha = null }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(),
            singleLine = true, enabled = !work.busy, modifier = Modifier.fillMaxWidth())
        Button(enabled = !work.busy && username.isNotBlank() && password.isNotBlank(), onClick = {
            work.run {
                val challenge = captcha
                val result = challenge?.let { bridge.verify(it) }
                accept(login.loginPassword(username, password, challenge, result))
            }
        }) { Text(if (captcha == null) "登录" else "在浏览器验证并登录") }
        LoginWorkState(work)
    }
}

@Composable
private fun LoginSmsForm(login: DesktopLoginRepository, bridge: DesktopCaptchaBridge, onComplete: (AccountSummary) -> Unit) {
    val work = rememberLoginWork()
    var regions by remember { mutableStateOf(resolveFallbackPhoneRegions()) }
    var region by remember { mutableStateOf(resolveDefaultPhoneRegion(regions)) }
    var regionQuery by remember { mutableStateOf("") }; var chooseRegion by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    var sms by remember { mutableStateOf<DesktopSmsSession?>(null) }
    var pendingCaptcha by remember { mutableStateOf<CaptchaData?>(null) }
    var risk by remember { mutableStateOf<DesktopLoginResult.RiskRequired?>(null) }
    val scope = rememberCoroutineScope()
    val instance = LocalDesktopLoginUpdateInstance.current
    DisposableEffect(scope, instance, login) {
        val job = launchTrackedLogin(scope, instance) {
            try { val loaded = login.phoneRegions(); regions = loaded; region = loaded.firstOrNull { it.cid == region.cid } ?: resolveDefaultPhoneRegion(loaded) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure }
        }
        onDispose { job.cancel() }
    }
    fun accept(result: DesktopLoginResult) { when(result) {
        is DesktopLoginResult.Complete -> { code = ""; onComplete(result.account) }
        is DesktopLoginResult.RiskRequired -> risk = result
        is DesktopLoginResult.CaptchaRequired -> { pendingCaptcha = result.captcha; work.notice = "登录需要再次验证，请重新获取短信" }
    } }
    if (risk != null) LoginRiskForm(risk!!, login, bridge, ::accept) { risk = null }
    else {
        Box {
            OutlinedButton(enabled = !work.busy, onClick = { chooseRegion = true }) { Text("${region.name} ${region.dialingCode}") }
            DropdownMenu(chooseRegion, { chooseRegion = false }, modifier = Modifier.heightIn(max = 330.dp)) {
                OutlinedTextField(regionQuery, { regionQuery = it }, label = { Text("搜索国家 / 区号") }, modifier = Modifier.padding(8.dp))
                filterPhoneRegions(regions, regionQuery).forEach { item -> DropdownMenuItem(text = { Text("${item.name} ${item.dialingCode}") }, onClick = {
                    region = item; chooseRegion = false; sms = null; pendingCaptcha = null; code = ""
                }) }
            }
        }
        OutlinedTextField(phone, { phone = it.filter(Char::isDigit); sms = null; pendingCaptcha = null; code = "" }, label = { Text("手机号") }, singleLine = true,
            enabled = !work.busy, modifier = Modifier.fillMaxWidth())
        Button(enabled = !work.busy && isPhoneDigitsValidForRegion(phone, region), onClick = {
            work.run {
                val challenge = pendingCaptcha ?: login.captcha()
                when (val result = login.sendSms(phone, region, challenge, bridge.verify(challenge))) {
                    is DesktopSmsResult.Sent -> { sms = result.session; pendingCaptcha = null; work.notice = "短信已发送" }
                    is DesktopSmsResult.CaptchaRequired -> { pendingCaptcha = result.captcha; work.notice = "服务器要求再次验证，请点击重新发送" }
                }
            }
        }) { Text(if (pendingCaptcha == null) "验证并发送短信" else "再次验证并发送短信") }
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("短信验证码") }, enabled = !work.busy && sms != null,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !work.busy && sms != null && code.length in 4..8, onClick = { work.run { accept(login.loginSms(requireNotNull(sms), code)) } }) { Text("登录") }
        LoginWorkState(work)
    }
}

@Composable
private fun LoginRiskForm(risk: DesktopLoginResult.RiskRequired, login: DesktopLoginRepository, bridge: DesktopCaptchaBridge,
    onResult: (DesktopLoginResult) -> Unit, onBack: () -> Unit) {
    val work = rememberLoginWork(); var captchaKey by remember(risk) { mutableStateOf<String?>(null) }; var code by remember(risk) { mutableStateOf("") }
    Text(risk.message.ifBlank { "登录需要手机安全验证" })
    Text("绑定手机 ${risk.hiddenPhone}", style = MaterialTheme.typography.titleMedium)
    Button(enabled = !work.busy, onClick = { work.run {
        val captcha = login.riskCaptcha(risk)
        captchaKey = login.sendRiskSms(risk, captcha, bridge.verify(captcha)); work.notice = "安全中心短信已发送"
    } }) { Text("验证并发送短信") }
    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("短信验证码") }, enabled = !work.busy && captchaKey != null,
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = !work.busy && captchaKey != null && code.length in 4..8, onClick = { work.run {
        onResult(login.verifyRiskSms(risk, requireNotNull(captchaKey), code))
    } }) { Text("验证并登录") }
    TextButton(enabled = !work.busy, onClick = onBack) { Text("返回") }
    LoginWorkState(work)
}

@Composable
private fun LoginCookieForm(repository: DesktopRepository, onComplete: (AccountSummary) -> Unit) {
    val work = rememberLoginWork(); var cookie by remember { mutableStateOf("") }
    val loginReturn = LocalDesktopLoginReturn.current
    val ownsAttempt = LocalDesktopLoginAttemptOwned.current
    val installed = LocalDesktopLoginInstalled.current
    Text("粘贴你自己的 B 站 Cookie（包含 SESSDATA）。保存前会验证账号。")
    OutlinedTextField(cookie, { cookie = it }, label = { Text("Cookie") }, visualTransformation = PasswordVisualTransformation(),
        minLines = 3, maxLines = 6, enabled = !work.busy, modifier = Modifier.fillMaxWidth())
    Button(enabled = !work.busy && cookie.isNotBlank(), onClick = { work.run { val account = repository.importCookies(cookie, loginReturn?.sourceEpoch, ownsAttempt, installed); cookie = ""; onComplete(account) } }) { Text("验证并登录") }
    LoginWorkState(work)
}

@Composable
private fun LoginAccountsForm(repository: DesktopRepository, login: DesktopLoginRepository, onComplete: (AccountSummary) -> Unit,
    onDismiss: () -> Unit) {
    val account by repository.account.collectAsState(); val accounts by repository.savedAccounts.collectAsState()
    val work = rememberLoginWork(); var remove by remember { mutableStateOf<DesktopStoredAccountInfo?>(null) }
    val loginReturn = LocalDesktopLoginReturn.current
    Text("当前账号：${account?.name ?: "访客"}")
    Text("账号凭证由当前 Windows 用户保护；退出登录保留已保存账号。", style = MaterialTheme.typography.bodySmall)
    accounts.forEach { stored ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(stored.account.avatar, "头像", Modifier.size(44.dp))
                    Column { Text(stored.account.name); Text("UID ${stored.account.mid} · ${if (stored.hasAccessToken) stored.accessTokenPlatform.uppercase() + " 授权" else "网页会话"}", style = MaterialTheme.typography.bodySmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !work.busy && account?.mid != stored.account.mid, onClick = { work.run { onComplete(repository.switchAccount(stored.account.mid, onAccountSwitched = { loginReturn?.cancel?.invoke() })) } }) { Text(if (account?.mid == stored.account.mid) "当前账号" else "切换") }
                    TextButton(enabled = !work.busy, onClick = { remove = stored }) { Text("移除") }
                }
            }
        }
    }
    if (accounts.isEmpty()) Text("没有已保存账号，请选择登录方式。")
    if (account != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !work.busy, onClick = { work.run { if (repository.refreshAccount() == null) {
                loginReturn?.cancel?.invoke(); onDismiss()
            } else work.notice = "账号资料已刷新" } }) { Text("刷新资料") }
            val active = accounts.firstOrNull { it.account.mid == account?.mid }
            if (active?.hasAccessToken == true && active.accessTokenPlatform == "tv") TextButton(enabled = !work.busy, onClick = { work.run { login.refreshTvToken(); work.notice = "TV 授权已更新" } }) { Text("更新 TV 授权") }
            TextButton(enabled = !work.busy, onClick = { work.run { repository.logout(); loginReturn?.cancel?.invoke(); onDismiss() } }) { Text("退出登录") }
        }
    }
    LoginWorkState(work)
    remove?.let { target -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除 ${target.account.name}？") },
        text = { Text("移除这台电脑保存的会话，重新登录后可再次添加。") }, confirmButton = {
            TextButton(enabled = !work.busy, onClick = { remove = null; work.run {
                var removedActive = false
                repository.removeSavedAccount(target.account.mid, onActiveAccountRemoved = {
                    loginReturn?.cancel?.invoke(); removedActive = true
                })
                if (removedActive) onDismiss()
            } }) { Text("移除") }
        }, dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
}
