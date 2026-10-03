package com.android.purebilibili.feature.login

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCard
import com.android.purebilibili.core.ui.components.AppCardDefaults
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.AppScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppOutlinedButton
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.components.AppOutlinedTextField
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppThemeAdaptiveTabRow
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.AppTopBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.purebilibili.core.store.TokenManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.launch

enum class LoginMethod {
    /** Official Bilibili TV QR login; the phone confirms on Bilibili's servers. */
    TV_QR,
    OFFICIAL_TV_SCAN,
    PASSWORD,
    SMS,
    COOKIE_IMPORT,
    BILIPAI_TRANSFER
}

// 设备间传输：会话 + 可选的设置/插件内容，纯二维码端到端加密。
// TV_QR logs this device in; OFFICIAL_TV_SCAN authorizes another device.
private const val ENABLE_BILIPAI_TRANSFER = true

internal fun resolveAvailableLoginMethods(): List<LoginMethod> =
    LoginMethod.entries.filter {
        ENABLE_BILIPAI_TRANSFER || it != LoginMethod.BILIPAI_TRANSFER
    }

internal fun resolveQrLoginReason(): String {
    return "推荐使用 TV 扫码登录，可获得更完整的播放登录态并解锁高画质播放能力。"
}

private sealed interface CaptchaRequest {
    data class Sms(val phone: String, val countryCid: Int) : CaptchaRequest
    data class Password(val phone: String, val password: String) : CaptchaRequest
    data object RiskSms : CaptchaRequest
}

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(),
    onLoginSuccess: () -> Unit,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phoneRegions by viewModel.phoneRegions.collectAsStateWithLifecycle()
    var selectedMethod by rememberSaveable { mutableStateOf(LoginMethod.TV_QR) }
    var transferVisible by rememberSaveable { mutableStateOf(false) }
    var captchaRequest by remember { mutableStateOf<CaptchaRequest?>(null) }
    var captchaManager by remember { mutableStateOf<CaptchaManager?>(null) }
    val activity = LocalActivity.current

    LaunchedEffect(state) {
        if (state is LoginState.Success) onLoginSuccess()
    }

    LaunchedEffect(selectedMethod) {
        if (selectedMethod == LoginMethod.BILIPAI_TRANSFER && !ENABLE_BILIPAI_TRANSFER) {
            selectedMethod = LoginMethod.TV_QR
            return@LaunchedEffect
        }
        captchaRequest = null
        viewModel.stopPolling()
        if (selectedMethod == LoginMethod.TV_QR) {
            viewModel.loadTvQrCode()
        } else {
            viewModel.resetPhoneLogin()
            if (selectedMethod == LoginMethod.SMS) {
                viewModel.loadPhoneRegions()
            }
        }
    }

    LaunchedEffect(state, captchaRequest, activity) {
        val hostActivity = activity ?: return@LaunchedEffect

        // Password-login risk flow uses safecenter/captcha/pre (RiskCaptchaReady).
        val riskReady = state as? LoginState.RiskCaptchaReady
        if (riskReady != null) {
            captchaManager?.destroy()
            captchaManager = CaptchaManager(hostActivity).also { manager ->
                manager.startCaptcha(
                    gt = riskReady.gt,
                    challenge = riskReady.challenge,
                    onSuccess = { validate, seccode, challenge ->
                        viewModel.sendRiskSmsCode(validate, seccode, challenge)
                    },
                    onFailed = { error ->
                        viewModel.showLoginError(error)
                    },
                    onCancel = {
                        viewModel.showLoginError("已取消风控安全验证")
                    }
                )
            }
            return@LaunchedEffect
        }

        val request = captchaRequest ?: return@LaunchedEffect
        val captchaData = (state as? LoginState.CaptchaReady)?.captchaData ?: return@LaunchedEffect
        captchaManager?.destroy()
        captchaManager = CaptchaManager(hostActivity).also { manager ->
            manager.startCaptcha(
                gt = captchaData.geetest?.gt.orEmpty(),
                challenge = captchaData.geetest?.challenge.orEmpty(),
                onSuccess = { validate, seccode, challenge ->
                    viewModel.saveCaptchaResult(validate, seccode, challenge)
                    when (request) {
                        is CaptchaRequest.Sms -> viewModel.sendSmsCode(
                            phone = request.phone,
                            countryCode = request.countryCid,
                        )
                        is CaptchaRequest.Password -> viewModel.loginByPassword(request.phone, request.password)
                        CaptchaRequest.RiskSms -> viewModel.sendRiskSmsCode(validate, seccode, challenge)
                    }
                },
                onFailed = { error ->
                    captchaRequest = null
                    viewModel.showLoginError(error)
                },
                onCancel = {
                    captchaRequest = null
                    viewModel.showLoginError("已取消安全验证")
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            captchaManager?.destroy()
            viewModel.stopPolling()
        }
    }

    LoginPage(
        state = state,
        selectedMethod = selectedMethod,
        phoneRegions = phoneRegions,
        onMethodSelected = { selectedMethod = it },
        onClose = onClose,
        onRefreshQr = viewModel::loadTvQrCode,
        onRequestSms = { phone, countryCid ->
            captchaRequest = CaptchaRequest.Sms(phone = phone, countryCid = countryCid)
            viewModel.beginSmsCodeRequest(phone = phone, countryCode = countryCid)
        },
        onSubmitSms = viewModel::loginBySms,
        onRequestPassword = { phone, password ->
            captchaRequest = CaptchaRequest.Password(phone, password)
            viewModel.beginPasswordLogin(phone, password)
        },
        onImportCookie = viewModel::loginByCookie,
        onOpenTransfer = { transferVisible = true },
        onContinueWithStandardSession = viewModel::continueWithStandardSession,
        onAuthorizeHighQuality = { selectedMethod = LoginMethod.TV_QR },
        qrAuthorizationContent = { OfficialQrAuthorizationContent(modifier = Modifier.fillMaxWidth().padding(20.dp)) },
        onPrepareRiskSms = viewModel::prepareRiskSmsCaptcha,
        onVerifyRiskSms = viewModel::verifyRiskSmsCode,
    )
    if (transferVisible) {
        BiliPaiTransferDialog(onDismiss = { transferVisible = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoginPage(
    state: LoginState,
    selectedMethod: LoginMethod,
    phoneRegions: List<PhoneRegion> = resolveFallbackPhoneRegions(),
    onMethodSelected: (LoginMethod) -> Unit,
    onClose: () -> Unit,
    onRefreshQr: () -> Unit,
    onRequestSms: (phone: String, countryCid: Int) -> Unit,
    onSubmitSms: (Int) -> Unit,
    onRequestPassword: (String, String) -> Unit,
    onImportCookie: (String) -> Unit,
    onOpenTransfer: () -> Unit = {},
    onContinueWithStandardSession: () -> Unit,
    onAuthorizeHighQuality: () -> Unit,
    onPrepareRiskSms: () -> Unit = {},
    onVerifyRiskSms: (String) -> Unit = {},
    qrAuthorizationContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AppSurface(modifier = modifier.fillMaxSize(), color = AppSurfaceTokens.chromeBackground()) {
        AppScaffold(
            containerColor = AppSurfaceTokens.chromeBackground(),
            topBar = {
                AppTopBar(
                    title = "登录",
                    actions = {
                        AppIconButton(onClick = onClose) {
                            AppIcon(Icons.Outlined.Close, contentDescription = "关闭登录")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppSurfaceTokens.chromeBackground()
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .testTag("login_scroll_content"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        LoginHeader(authorizingOtherDevice = selectedMethod == LoginMethod.OFFICIAL_TV_SCAN)
                    }
                    item {
                        LoginMethodTabs(
                            selectedMethod = selectedMethod,
                            onMethodSelected = onMethodSelected
                        )
                    }
                    item {
                        LoginStateMessage(state)
                    }
                    if (state is LoginState.HighQualityAuthorization) {
                        item {
                            HighQualityAuthorizationCard(
                                onContinue = onContinueWithStandardSession,
                                onAuthorize = onAuthorizeHighQuality
                            )
                        }
                    }
                    item {
                        when (selectedMethod) {
                            LoginMethod.TV_QR -> TvQrLoginContent(state, onRefreshQr)
                            LoginMethod.OFFICIAL_TV_SCAN -> AppCard(modifier = Modifier.fillMaxWidth()) {
                                qrAuthorizationContent()
                            }
                            LoginMethod.PASSWORD -> PasswordLoginContent(
                                state = state,
                                onSubmit = onRequestPassword,
                                onPrepareRiskSms = onPrepareRiskSms,
                                onVerifyRiskSms = onVerifyRiskSms,
                            )
                            LoginMethod.SMS -> SmsLoginContent(
                                state = state,
                                phoneRegions = phoneRegions,
                                onRequestCode = onRequestSms,
                                onSubmitCode = onSubmitSms,
                            )
                            LoginMethod.COOKIE_IMPORT -> CookieImportContent(state, onImportCookie)
                            LoginMethod.BILIPAI_TRANSFER -> BiliPaiTransferEntry(onOpenTransfer)
                        }
                    }
                    item {
                        var showAgreement by remember { mutableStateOf(false) }
                        com.android.purebilibili.core.ui.components.AppTextButton(
                            onClick = { showAgreement = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AppText(
                                text = "继续即表示你同意用户协议和隐私政策，点击查看全文。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (showAgreement) {
                            com.android.purebilibili.feature.agreement.UserAgreementReviewDialog(
                                onDismiss = { showAgreement = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginHeader(authorizingOtherDevice: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(text = if (authorizingOtherDevice) "授权其他设备登录" else "登录 BiliPai", style = MaterialTheme.typography.headlineMedium)
        AppText(
            text = if (authorizingOtherDevice) "使用本机已登录的 B 站账号，确认其他设备的登录请求。"
                else "选择一种方式继续，你的观看进度和账号信息会同步到当前设备。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoginMethodTabs(
    selectedMethod: LoginMethod,
    onMethodSelected: (LoginMethod) -> Unit,
    modifier: Modifier = Modifier
) {
    AppThemeAdaptiveTabRow(
        options = resolveAvailableLoginMethods().map { method ->
            AppSegmentOption(method, loginMethodLabel(method))
        },
        selectedValue = selectedMethod,
        onSelectionChange = onMethodSelected,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally),
        scrollable = true,
    )
}

private fun loginMethodLabel(method: LoginMethod): String = when (method) {
    LoginMethod.TV_QR -> "扫码登录"
    LoginMethod.OFFICIAL_TV_SCAN -> "扫码授权"
    LoginMethod.PASSWORD -> "密码登录"
    LoginMethod.SMS -> "短信登录"
    LoginMethod.COOKIE_IMPORT -> "Cookie 导入"
    LoginMethod.BILIPAI_TRANSFER -> "BiliPai 传输"
}

@Composable
private fun LoginStateMessage(state: LoginState, modifier: Modifier = Modifier) {
    val message = (state as? LoginState.Error)?.msg ?: return
    AppCard(
        modifier = modifier.fillMaxWidth(),
        colors = AppCardDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        AppText(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun BiliPaiTransferEntry(onOpen: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("在两台 BiliPai 设备之间迁移登录会话、设置与插件", style = MaterialTheme.typography.titleMedium)
            AppText("内容只在设备端加密和解密，不经过服务器。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { AppText("开始传输") }
        }
    }
}

@Composable
private fun BiliPaiTransferDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session = remember { BiliPaiTransferSession(context) }
    var receiving by rememberSaveable { mutableStateOf(true) }
    var requestQr by remember { mutableStateOf<String?>(null) }
    var qrChunks by remember { mutableStateOf<List<String>>(emptyList()) }
    var chunkIndex by rememberSaveable { mutableIntStateOf(0) }
    var requestAccepted by remember { mutableStateOf(false) }
    var pendingPayload by remember { mutableStateOf<BiliPaiTransferPayload?>(null) }
    var importReport by remember { mutableStateOf<BiliPaiTransferImportReport?>(null) }
    var chunkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var message by remember { mutableStateOf("选择接收或发送") }
    var inventory by remember {
        mutableStateOf(BiliPaiTransferContentInventory(0, emptyList(), emptyList(), emptyList(), false))
    }
    var selection by remember { mutableStateOf(BiliPaiTransferContentSelection()) }

    androidx.compose.runtime.LaunchedEffect(receiving) {
        requestQr = null; qrChunks = emptyList(); chunkIndex = 0; requestAccepted = false
        pendingPayload = null; importReport = null; chunkProgress = null
        message = if (receiving) {
            requestQr = BiliPaiTransferCodec.encodeRequest(session.beginReceive())
            "请让发送设备扫描此二维码"
        } else {
            "请扫描接收设备的请求二维码"
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText("BiliPai 安全传输") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { receiving = true }) { AppText("接收账号") }
                    AppOutlinedButton(onClick = { receiving = false }) { AppText("发送账号") }
                }
                AppText(message, textAlign = TextAlign.Center)

                when {
                    importReport != null -> {
                        importReport!!.items.forEach { item ->
                            AppText(
                                (if (item.success) "✓ " else "✗ ") + item.name +
                                    (item.message?.let { "（$it）" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.success) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    // Receiver: decrypted payload awaiting import.
                    pendingPayload != null -> {
                        AppText(transferPreviewText(pendingPayload!!), textAlign = TextAlign.Center)
                        AppButton(onClick = {
                            scope.launch {
                                if (session.confirmImport(pendingPayload!!)) {
                                    importReport = (session.state as? BiliPaiTransferState.Completed)?.importReport
                                        ?: BiliPaiTransferImportReport(items = emptyList())
                                    message = "导入完成"
                                } else {
                                    message = (session.state as? BiliPaiTransferState.Failed)?.message ?: "导入失败"
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            AppText("确认导入 MID ${pendingPayload!!.session.mid}")
                        }
                    }

                    // Sender: chunk pager.
                    qrChunks.isNotEmpty() -> {
                        val total = qrChunks.size
                        val index = chunkIndex.coerceIn(0, total - 1)
                        val current = qrChunks[index]
                        AppText(
                            if (total == 1) "加密二维码" else "分块 ${index + 1}/$total",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        val bitmap = remember(current) { runCatching { transferQrBitmap(current) }.getOrNull() }
                        if (bitmap != null) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "BiliPai 传输二维码",
                                modifier = Modifier.size(240.dp))
                        }
                        if (total > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppOutlinedButton(onClick = { if (chunkIndex > 0) chunkIndex-- }) { AppText("上一张") }
                                AppOutlinedButton(onClick = { if (chunkIndex < total - 1) chunkIndex++ }) { AppText("下一张") }
                            }
                            AppText(
                                "请按顺序让对方依次扫描全部分块",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    // Sender: content selection after accepting the receiver request.
                    !receiving && requestAccepted -> {
                        TransferContentSelectionUi(inventory, selection) { selection = it }
                        AppButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val bundle = BiliPaiSessionBundle(
                                        mid = TokenManager.midCache ?: 0L,
                                        sessData = TokenManager.sessDataCache.orEmpty(),
                                        csrf = TokenManager.csrfCache.orEmpty(),
                                        // Long-lived app tokens never travel in a QR; Cookie + CSRF
                                        // is enough to establish the account session.
                                        accessToken = "",
                                        refreshToken = "",
                                        accessTokenPlatform = TokenManager.accessTokenPlatformCache,
                                        buvid3 = TokenManager.buvid3Cache.orEmpty(),
                                        isVip = TokenManager.isVipCache,
                                    )
                                    require(bundle.mid > 0 && bundle.sessData.isNotBlank()) { "当前设备没有可传输的登录会话" }
                                    val chunks = session.createEnvelope(bundle, selection)
                                    qrChunks = chunks
                                    chunkIndex = 0
                                    message = if (chunks.size == 1) "请让接收设备扫描此加密二维码"
                                    else "内容较多，已分为 ${chunks.size} 张分块二维码"
                                }.onFailure { message = it.message ?: "生成二维码失败" }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { AppText("生成加密二维码") }
                    }

                    // Receiver: request QR + scanner (or chunk progress while collecting).
                    receiving -> {
                        if (chunkProgress != null) {
                            AppText("已接收 ${chunkProgress!!.first}/${chunkProgress!!.second} 个分块，请继续扫描")
                        }
                        requestQr?.let { value ->
                            val bitmap = remember(value) { runCatching { transferQrBitmap(value) }.getOrNull() }
                            if (bitmap != null) {
                                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "BiliPai 传输请求二维码",
                                    modifier = Modifier.size(220.dp))
                            }
                        }
                        TransferScannerOrNothing { raw ->
                            scope.launch {
                                runCatching {
                                    val payload = session.acceptEnvelope(raw)
                                    if (payload == null) {
                                        chunkProgress = session.chunkBuffer.receivedCount() to
                                            session.chunkBuffer.total
                                        message = "分块接收中"
                                    } else {
                                        pendingPayload = payload
                                        chunkProgress = null
                                        message = "已解密，请确认导入"
                                    }
                                }.onFailure { message = it.message ?: "二维码无效" }
                            }
                        }
                    }

                    // Sender initial: scanner for the receiver request.
                    else -> {
                        TransferScannerOrNothing { raw ->
                            scope.launch {
                                runCatching {
                                    session.acceptRequest(raw)
                                    inventory = BiliPaiTransferContent.inventory(context)
                                    selection = BiliPaiTransferContentSelection(
                                        settings = true,
                                        jsPluginIds = inventory.jsPlugins.mapTo(mutableSetOf()) { it.id },
                                        skinIds = inventory.skins.mapTo(mutableSetOf()) { it.id },
                                        kotlinPluginIds = inventory.kotlinPlugins.mapTo(mutableSetOf()) { it.id },
                                    )
                                    requestAccepted = true
                                    message = "选择要携带的内容后生成二维码"
                                }.onFailure { message = it.message ?: "二维码无效" }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { AppTextButton(onClick = onDismiss) { AppText("取消") } },
    )
}

@Composable
private fun TransferScannerOrNothing(onCode: (String) -> Unit) {
    BiliPaiTransferScanner(onCode = onCode, modifier = Modifier.size(180.dp), acceptAnyQr = true)
}

private fun transferPreviewText(payload: BiliPaiTransferPayload): String = buildString {
    append("已解密 MID ${payload.session.mid}，请确认导入")
    val extras = mutableListOf<String>()
    payload.settingsJson?.let { extras.add("设置") }
    payload.jsonRules?.let { extras.add("JSON 规则插件") }
    if (payload.jsPlugins.isNotEmpty()) extras.add("JS 插件×${payload.jsPlugins.size}")
    if (payload.skins.isNotEmpty()) extras.add("皮肤×${payload.skins.size}")
    if (payload.kotlinPlugins.isNotEmpty()) extras.add("插件包×${payload.kotlinPlugins.size}")
    if (extras.isNotEmpty()) append("；携带：${extras.joinToString("、")}")
}

@Composable
private fun TransferContentSelectionUi(
    inventory: BiliPaiTransferContentInventory,
    selection: BiliPaiTransferContentSelection,
    onChange: (BiliPaiTransferContentSelection) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TransferCheckRow("共享设置（${inventory.settingCount} 项）", selection.settings) { checked ->
            onChange(selection.copy(settings = checked))
        }
        if (inventory.hasJsonRules) {
            TransferCheckRow("JSON 规则插件", selection.jsonRules) { checked ->
                onChange(selection.copy(jsonRules = checked))
            }
        }
        TransferGroup("JS 插件", inventory.jsPlugins, selection.jsPluginIds) { ids ->
            onChange(selection.copy(jsPluginIds = ids))
        }
        TransferGroup("皮肤", inventory.skins, selection.skinIds) { ids ->
            onChange(selection.copy(skinIds = ids))
        }
        TransferGroup("外部插件包", inventory.kotlinPlugins, selection.kotlinPluginIds) { ids ->
            onChange(selection.copy(kotlinPluginIds = ids))
        }
    }
}

@Composable
private fun TransferCheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChecked)
        AppText(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TransferGroup(
    label: String,
    items: List<BiliPaiTransferContentItem>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
) {
    if (items.isEmpty()) return
    AppText("$label（${selected.size}/${items.size}）", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEach { item ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = item.id in selected,
                onCheckedChange = { checked ->
                    onSelected(if (checked) selected + item.id else selected - item.id)
                },
            )
            AppText(item.name, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun transferQrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
    for (x in 0 until 512) for (y in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    return bitmap
}

@Composable
private fun HighQualityAuthorizationCard(
    onContinue: () -> Unit,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        colors = AppCardDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppText(
                text = "基础登录已完成",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            AppText(
                text = "当前登录未返回高画质播放凭据。扫码可补充 1080P60、4K、HDR 等画质所需授权。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            AppButton(onClick = onAuthorize, modifier = Modifier.fillMaxWidth()) {
                AppText("扫码授权高画质")
            }
            AppOutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                AppText("稍后使用")
            }
        }
    }
}

@Composable
private fun TvQrLoginContent(
    state: LoginState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppText(text = "扫码登录", style = MaterialTheme.typography.titleLarge)
            AppText(
                text = resolveQrLoginReason(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            when (state) {
                is LoginState.QrCode, is LoginState.Scanned -> {
                    val bitmap = when (state) {
                        is LoginState.QrCode -> state.bitmap
                        is LoginState.Scanned -> state.bitmap
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(232.dp).testTag("login_qr_code")
                    )
                    if (state is LoginState.Scanned) {
                        AppText(
                            text = "已扫码，请在手机上确认登录。",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                LoginState.Loading -> AdaptiveLoadingIndicator(size = 48.dp)
                else -> AppIcon(
                    imageVector = Icons.Outlined.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(232.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppOutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                AppIcon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                AppText("刷新二维码")
            }
        }
    }
}

@Composable
private fun PasswordLoginContent(
    state: LoginState,
    onSubmit: (String, String) -> Unit,
    onPrepareRiskSms: () -> Unit,
    onVerifyRiskSms: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var riskCode by rememberSaveable { mutableStateOf("") }
    val isLoading = state is LoginState.Loading ||
        state is LoginState.CaptchaReady ||
        state is LoginState.RiskCaptchaReady
    val riskRequired = state as? LoginState.RiskVerificationRequired
    val riskSmsSent = state as? LoginState.RiskSmsSent
    val riskHideTel = riskRequired?.hideTel
        ?: riskSmsSent?.hideTel
        ?: (state as? LoginState.RiskCaptchaReady)?.hideTel
    val inRiskFlow = riskHideTel != null

    LoginFormCard(title = if (inRiskFlow) "安全验证" else "密码登录", modifier = modifier) {
        if (inRiskFlow) {
            AppText(
                text = riskRequired?.message
                    ?: "本次登录环境存在风险，需验证绑定手机号。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppText(
                text = "绑定手机：${riskHideTel.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (riskSmsSent != null) {
                AppOutlinedTextField(
                    value = riskCode,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all { it.isDigit() }) {
                            riskCode = value
                        }
                    },
                    labelText = "短信验证码",
                    label = { AppText("短信验证码") },
                    leadingIcon = { AppIcon(Icons.Outlined.Lock, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                riskSmsSent.errorMessage?.let { err ->
                    AppText(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                AppButton(
                    onClick = { onVerifyRiskSms(riskCode) },
                    enabled = riskCode.length == 6 && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppText("确认验证并登录")
                }
                AppOutlinedButton(
                    onClick = {
                        riskCode = ""
                        onPrepareRiskSms()
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppText("重新发送验证码")
                }
            } else {
                riskRequired?.errorMessage?.let { err ->
                    AppText(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                AppButton(
                    onClick = onPrepareRiskSms,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppText(if (isLoading) "准备验证中…" else "发送短信验证码")
                }
                AppText(
                    text = "将向绑定手机发送验证码。若收不到短信，请改用扫码或 Cookie 导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            AppOutlinedTextField(
                value = username,
                onValueChange = { username = it.filterNot(Char::isWhitespace) },
                labelText = "邮箱 / 手机号",
                label = { AppText("邮箱 / 手机号") },
                leadingIcon = { AppIcon(Icons.Outlined.Phone, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            AppOutlinedTextField(
                value = password,
                onValueChange = { password = it },
                labelText = "密码",
                label = { AppText("密码") },
                leadingIcon = { AppIcon(Icons.Outlined.Lock, contentDescription = null) },
                trailingIcon = {
                    AppIconButton(onClick = { passwordVisible = !passwordVisible }) {
                        AppIcon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            AppText(
                text = "提交前需要完成安全验证。若触发环境风控，将引导绑定手机二次验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppButton(
                onClick = { onSubmit(username, password) },
                enabled = username.isNotBlank() && password.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppText("验证并登录")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsLoginContent(
    state: LoginState,
    phoneRegions: List<PhoneRegion>,
    onRequestCode: (phone: String, countryCid: Int) -> Unit,
    onSubmitCode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var selectedCid by rememberSaveable { mutableIntStateOf(DEFAULT_PHONE_REGION_CID) }
    var showRegionPicker by remember { mutableStateOf(false) }
    val regions = phoneRegions.ifEmpty { resolveFallbackPhoneRegions() }
    val selectedRegion = remember(selectedCid, regions) {
        regions.firstOrNull { it.cid == selectedCid } ?: resolveDefaultPhoneRegion(regions)
    }
    val phoneEligible = isPhoneEligibleForCaptcha(phone, selectedRegion)
    val codeSent = state is LoginState.SmsSent
    val isLoading = state is LoginState.Loading || state is LoginState.CaptchaReady

    LaunchedEffect(selectedRegion.maxDigits) {
        if (phone.length > selectedRegion.maxDigits) {
            phone = phone.take(selectedRegion.maxDigits)
        }
    }
    LaunchedEffect(regions) {
        if (regions.none { it.cid == selectedCid }) {
            selectedCid = resolveDefaultPhoneRegion(regions).cid
        }
    }

    LoginFormCard(title = "短信验证码登录", modifier = modifier) {
        // 国家/地区选择：Material3 底部表 + 搜索，数据来自 passport 官方列表。
        AppOutlinedButton(
            onClick = { showRegionPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            AppIcon(Icons.Outlined.Phone, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            AppText(
                text = "${selectedRegion.dialingCode}  ${selectedRegion.name}",
                maxLines = 1,
            )
        }
        AppOutlinedTextField(
            value = phone,
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit)
                if (digits.length <= selectedRegion.maxDigits) {
                    phone = digits
                }
            },
            labelText = "手机号",
            label = { AppText("手机号") },
            prefix = { AppText("${selectedRegion.dialingCode} ") },
            leadingIcon = { AppIcon(Icons.Outlined.Phone, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            supportingText = {
                AppText(
                    text = if (phone.isNotBlank() && !phoneEligible) {
                        "号码长度需为 ${selectedRegion.minDigits}-${selectedRegion.maxDigits} 位"
                    } else {
                        "支持国际区号，区号列表来自 B 站官方接口"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (codeSent || code.isNotEmpty()) {
            AppOutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                labelText = "短信验证码",
                label = { AppText("短信验证码") },
                leadingIcon = { AppIcon(Icons.Outlined.Lock, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AppText(
            text = "发送验证码前需要完成安全验证。验证码有效期与发送频率以服务端规则为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (codeSent || code.isNotEmpty()) {
            AppButton(
                onClick = { onSubmitCode(code.toIntOrNull() ?: 0) },
                enabled = code.length == 6 && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppText("登录")
            }
        } else {
            AppButton(
                onClick = { onRequestCode(phone, resolveSmsApiCid(selectedRegion)) },
                enabled = phoneEligible && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppText("获取验证码")
            }
        }
    }

    if (showRegionPicker) {
        PhoneRegionPickerSheet(
            regions = regions,
            selectedCid = selectedRegion.cid,
            onSelect = { region ->
                selectedCid = region.cid
                showRegionPicker = false
            },
            onDismiss = { showRegionPicker = false },
        )
    }
}

/**
 * 成熟 Material3 底部表 + 搜索筛选国家/地区，避免自造滚轮/列表组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneRegionPickerSheet(
    regions: List<PhoneRegion>,
    selectedCid: Int,
    onSelect: (PhoneRegion) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(regions, query) {
        filterPhoneRegions(regions, query)
    }
    val common = remember(filtered) { filtered.filter { it.isCommon } }
    val others = remember(filtered) { filtered.filterNot { it.isCommon } }

    com.android.purebilibili.core.ui.AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(
                text = "选择国家或地区",
                style = MaterialTheme.typography.titleMedium,
            )
            AppOutlinedTextField(
                value = query,
                onValueChange = { query = it },
                labelText = "搜索名称或区号",
                label = { AppText("搜索名称或区号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (common.isNotEmpty()) {
                    item {
                        AppText(
                            text = "常用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(common.size, key = { common[it].cid }) { index ->
                        val region = common[index]
                        PhoneRegionPickerRow(
                            region = region,
                            selected = region.cid == selectedCid,
                            onClick = { onSelect(region) },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item {
                        AppText(
                            text = "全部",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(others.size, key = { "o-${others[it].cid}" }) { index ->
                        val region = others[index]
                        PhoneRegionPickerRow(
                            region = region,
                            selected = region.cid == selectedCid,
                            onClick = { onSelect(region) },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        AppText(
                            text = "无匹配地区",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneRegionPickerRow(
    region: PhoneRegion,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        AppSurfaceTokens.surface()
    }
    AppSurface(
        onClick = onClick,
        color = bg,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = region.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                AppText(
                    text = region.dialingCode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                AppText(
                    text = "已选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CookieImportContent(
    state: LoginState,
    onImport: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var cookieHeader by rememberSaveable { mutableStateOf("") }
    val isLoading = state is LoginState.Loading

    LoginFormCard(title = "Cookie 导入", modifier = modifier) {
        AppText(
            text = "粘贴浏览器中的完整 Cookie 字符串。导入前会先验证账号，验证失败不会覆盖当前登录状态。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppOutlinedTextField(
            value = cookieHeader,
            onValueChange = { cookieHeader = it },
            labelText = "Cookie",
            label = { AppText("Cookie") },
            leadingIcon = { AppIcon(Icons.Outlined.ContentPaste, contentDescription = null) },
            minLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        AppButton(
            onClick = { onImport(cookieHeader) },
            enabled = cookieHeader.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            AppText("验证并导入")
        }
    }
}

@Composable
private fun LoginFormCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppText(text = title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
