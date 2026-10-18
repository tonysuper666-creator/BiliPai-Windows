package com.bilipai.desktop.nvidia

import com.bilipai.desktop.player.NvidiaVideoBackend

// Single owner of every user-visible NVIDIA enhancement string. Pure: no I/O, no clock.
//
// Wording rules (scanned by a source test): a request is never worded as applied; a driver
// version number appears only in the Blackwell driver text; the tier word and the tier suffix
// exist only behind TIER_WORD_ENABLED on the verified shared-core route, so a pipeline the
// application cannot configure or confirm never gets a tier name.
// Every template in this file puts braces around the variable: the Chinese text that follows
// would otherwise extend the identifier.

/**
 * Root switch. Stays false until a hardware run has proved a quality-4 presentation on the
 * verified shared-core route; it is the only gate for the tier word.
 */
internal const val TIER_WORD_ENABLED: Boolean = false

/** One status line is built from exactly one of these. */
internal enum class DesktopNvidiaStatusKind {
    OFF,
    PROBING,
    NO_NVIDIA,
    UNSUPPORTED,
    UNIDENTIFIED,
    STANDBY,
    WAITING,
    DIRECT,
    OVER_BUDGET,
    PENDING,
    SUBMITTED,
    PROOF_DELAYED,
    ACTIVE,
    STEPPED_DOWN,
    GUARD_BYPASS,
    PAUSED_GAME,
    PAUSED_BATTERY,
    PAUSED_REMOTE,
    FAILED_REMEMBERED,
    FAILED_THIS_VIDEO,
    FAILED_EXTENDED_RANGE,
    COLOR_UNSUPPORTED,
    LUID_MISMATCH,
    LUID_UNKNOWN,
    NON_D3D11_DECODE,
}

internal data class DesktopNvidiaStatusInput(
    val kind: DesktopNvidiaStatusKind,
    /** Raw adapter description; the short name is derived from it. */
    val gpuDescription: String? = null,
    val route: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,
    val tier: NvidiaTier? = null,
    /** Needed for UNSUPPORTED; a missing reason is worded as the unverified adapter case. */
    val unsupportedReason: NvidiaUnsupportedReason? = null,
    val crossAdapter: Boolean = false,
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    /** True only when the existing HDR presentation proof holds. */
    val hdrPresented: Boolean = false,
    /** Quality level the guard stepped down to, for STEPPED_DOWN. */
    val qualityLevel: Int? = null,
)

internal object DesktopNvidiaStatusText {
    const val DEFAULT_GPU_LABEL: String = "NVIDIA 显卡"
    const val SEPARATOR: String = " · "

    // States that need no GPU name.
    const val OFF_GPU_UNKNOWN: String = "自动增强已关闭，使用原画播放。"
    const val PROBING: String = "正在检测显卡…"
    const val NO_NVIDIA: String = "未检测到可用的 NVIDIA RTX 显卡，使用原画播放。设置会保留，插入或启用显卡后自动生效。"
    const val DRIVER_ADVISORY: String = "驱动版本较旧，增强可能不可用，建议更新驱动。"
    const val HDR_DRIVER_OLD: String = "驱动版本较旧，SDR 转 HDR 暂不可用，已仅使用清晰度增强。"
    const val NOT_PHYSICAL: String = "只检测到虚拟显示适配器，未启用增强。"
    const val PIN_UNSAFE: String = "无法确认播放使用的是实体 NVIDIA 显卡（系统中存在同名虚拟适配器），为保证显示正确，使用原画。"
    const val UNIDENTIFIED: String = "未能读取显卡详情，将按保守档位处理。"
    const val GUARD_BYPASS: String = "已为保证流畅停止增强，本视频保持原画。"
    const val PAUSED_GAME: String = "检测到其他全屏应用（可能是游戏），已暂停增强让出显卡。"
    const val PAUSED_BATTERY: String = "正在使用电池供电，已暂停增强（播放仍使用独立显卡）。"
    const val PAUSED_REMOTE: String = "当前为远程或虚拟显示输出，已暂停增强。"
    const val FAILED_EXTENDED_RANGE: String = "这段视频的分辨率超出已验证范围，已使用原画。"
    const val COLOR_UNSUPPORTED: String = "当前视频色彩格式不支持增强，可关闭增强。"
    const val LUID_MISMATCH: String = "播放器实际使用的显卡与检测到的物理 NVIDIA 显卡不一致，继续原画播放。"
    const val LUID_UNKNOWN: String = "无法确认播放器实际使用的显卡，继续原画播放。"
    const val NON_D3D11_DECODE: String = "当前不是 D3D11 硬件解码，无法进行 NVIDIA 增强。"
    const val TIER_DENIES_HDR: String = "为保持流畅，当前档位仅启用清晰度增强。"
    const val SWITCH_SUBTITLE_CAPABLE: String = "检测到支持的 NVIDIA RTX 显卡时自动开启，并按显卡性能选择配置。不会修改显卡或系统设置。"
    const val SWITCH_SUBTITLE_NOT_CAPABLE: String = "未检测到可用的 NVIDIA RTX 显卡，此功能不会启用。"
    const val QUALITY_CEILING_HELPER: String = "默认「最高」。自动模式按显卡性能选择实际等级，不会超过此上限。"

    // Text that follows "{GPU} · ".
    const val OFF_GPU_KNOWN_TAIL: String = "自动增强已关闭，可随时开启"
    const val STANDBY_TAIL: String = "播放视频时自动增强"
    const val WAITING_TAIL: String = "等待视频画面"
    const val DIRECT_TAIL: String = "当前画面尺寸无需放大，原画直出"
    const val OVER_BUDGET_TAIL: String = "片源分辨率或帧率超出当前显卡的实时处理范围，原画直出"
    const val PENDING_TAIL: String = "正在准备增强…"
    const val SUBMITTED_TAIL: String = "已提交增强，等待画面反馈"
    const val PROOF_DELAYED_TAIL: String = "增强已提交，但暂时无法确认画面已显示（多显示器、窗口被遮挡或全屏切换时会出现）。"
    const val ACTIVE_VEYRA_TAIL: String = "增强画面已显示"
    const val ACTIVE_HDR_SUFFIX: String = "；SDR 转 HDR 已显示"
    const val ACTIVE_DRIVER_TAIL: String = "已向 NVIDIA 驱动请求增强；是否生效取决于驱动或控制面板设置，本程序无法确认。"
    const val DRIVER_NOT_ACTIVE_TAIL: String = "使用驱动增强路径，质量与强度不可调。"
    const val FAILED_REMEMBERED_TAIL: String = "此显卡与驱动组合上增强曾失败，已改用原画；更新驱动或组件后自动重试。"
    const val FAILED_THIS_VIDEO_TAIL: String = "这段视频暂时无法增强，已使用原画。"
    const val CROSS_ADAPTER_SUFFIX: String = "（视频显示在另一块显卡的屏幕上，已降低档位）"

    // Templates that carry the GPU name or a number.
    fun preRtx(gpu: String): String = "${gpu} 不支持 RTX 视频增强（需要 GeForce RTX 20 系列或更新），使用原画播放。"

    fun driverOld(gpu: String): String = "${gpu} 需要较新的显卡驱动（建议 572.16 或更新），请更新驱动后重启应用。"

    fun vramLow(gpu: String): String = "${gpu} 显存不足，使用原画播放。"

    fun steppedDownTail(qualityLevel: Int): String =
        "已自动降至质量 ${qualityLevel.coerceIn(1, 4)}/4 以保持流畅（本视频内保持）"

    fun requestedDetail(qualityLevel: Int, intensityLabel: String): String =
        "已请求：质量 ${qualityLevel.coerceIn(1, 4)}/4 · 强度 ${intensityLabel}"
}

/** Strips a leading "NVIDIA " and "GeForce ", control characters and extra spaces. */
internal fun desktopNvidiaGpuShortName(description: String?): String {
    if (description == null) return DesktopNvidiaStatusText.DEFAULT_GPU_LABEL
    val cleaned = StringBuilder()
    for (ch in description) cleaned.append(if (Character.isISOControl(ch)) ' ' else ch)
    var text = cleaned.toString().trim()
    for (prefix in listOf("NVIDIA", "GeForce")) {
        if (text.length >= prefix.length && text.startsWith(prefix, ignoreCase = true) &&
            (text.length == prefix.length || text[prefix.length].isWhitespace())
        ) {
            text = text.substring(prefix.length).trim()
        }
    }
    text = text.replace(Regex("\\s+"), " ")
    return if (text.isEmpty()) DesktopNvidiaStatusText.DEFAULT_GPU_LABEL else text
}

/**
 * The tier word, or null. Non-null only when the root switch is on, a tier is known and the
 * route is the verified shared core. The driver route never gets one.
 */
internal fun desktopNvidiaTierWord(tier: NvidiaTier?, route: NvidiaVideoBackend, enabled: Boolean): String? {
    if (!enabled || tier == null || route != NvidiaVideoBackend.VEYRA_CORE) return null
    return when (tier) {
        NvidiaTier.ULTRA -> "极致"
        NvidiaTier.HIGH -> "高"
        NvidiaTier.BALANCED -> "均衡"
        NvidiaTier.LIGHT -> "轻量"
    }
}

/** The "已请求" detail line: shared-core route only, because the driver route cannot apply quality or strength. */
internal fun desktopNvidiaRequestedDetail(route: NvidiaVideoBackend, qualityLevel: Int, intensityLabel: String): String? {
    if (route != NvidiaVideoBackend.VEYRA_CORE) return null
    return DesktopNvidiaStatusText.requestedDetail(qualityLevel, intensityLabel)
}

/** Switch subtitle: capable means a supported adapter is present or the probe is still undecided. */
internal fun desktopNvidiaSwitchSubtitle(capable: Boolean): String =
    if (capable) DesktopNvidiaStatusText.SWITCH_SUBTITLE_CAPABLE else DesktopNvidiaStatusText.SWITCH_SUBTITLE_NOT_CAPABLE

private fun desktopNvidiaUnsupportedText(reason: NvidiaUnsupportedReason?, gpu: String): String = when (reason) {
    NvidiaUnsupportedReason.PRE_RTX -> DesktopNvidiaStatusText.preRtx(gpu)
    NvidiaUnsupportedReason.DRIVER_OLD -> DesktopNvidiaStatusText.driverOld(gpu)
    NvidiaUnsupportedReason.VRAM_LOW -> DesktopNvidiaStatusText.vramLow(gpu)
    NvidiaUnsupportedReason.NOT_PHYSICAL -> DesktopNvidiaStatusText.NOT_PHYSICAL
    NvidiaUnsupportedReason.PIN_UNSAFE -> DesktopNvidiaStatusText.PIN_UNSAFE
    NvidiaUnsupportedReason.PHYSICAL_UNVERIFIED -> DesktopNvidiaStatusText.PIN_UNSAFE
    null -> DesktopNvidiaStatusText.PIN_UNSAFE
}

/**
 * The one status line: "{GPU short name} · {state}". The tier word and its suffix are inserted
 * after the GPU name only when tierWordEnabled and the route is the shared core. The
 * cross-adapter suffix is appended to the lines of the live pipeline. On the driver route a
 * pending, submitted or proof-delayed line says the route is fixed, because nothing can be confirmed.
 */
internal fun desktopNvidiaStatusLine(
    input: DesktopNvidiaStatusInput,
    tierWordEnabled: Boolean = TIER_WORD_ENABLED,
): String {
    val gpuKnown = !input.gpuDescription.isNullOrBlank()
    val gpu = desktopNvidiaGpuShortName(input.gpuDescription)
    val tierWord = desktopNvidiaTierWord(input.tier, input.route, tierWordEnabled)
    val lead = if (tierWord == null) gpu else "${gpu} · ${tierWord}档位"
    val cross = if (input.crossAdapter) DesktopNvidiaStatusText.CROSS_ADAPTER_SUFFIX else ""
    val sharedCore = input.route == NvidiaVideoBackend.VEYRA_CORE
    val separator = DesktopNvidiaStatusText.SEPARATOR
    return when (input.kind) {
        DesktopNvidiaStatusKind.OFF ->
            if (gpuKnown) "${gpu}${separator}${DesktopNvidiaStatusText.OFF_GPU_KNOWN_TAIL}" else DesktopNvidiaStatusText.OFF_GPU_UNKNOWN
        DesktopNvidiaStatusKind.PROBING -> DesktopNvidiaStatusText.PROBING
        DesktopNvidiaStatusKind.NO_NVIDIA -> DesktopNvidiaStatusText.NO_NVIDIA
        DesktopNvidiaStatusKind.UNSUPPORTED -> desktopNvidiaUnsupportedText(input.unsupportedReason, gpu)
        DesktopNvidiaStatusKind.UNIDENTIFIED -> DesktopNvidiaStatusText.UNIDENTIFIED
        DesktopNvidiaStatusKind.STANDBY -> "${lead}${separator}${DesktopNvidiaStatusText.STANDBY_TAIL}${cross}"
        DesktopNvidiaStatusKind.WAITING -> "${lead}${separator}${DesktopNvidiaStatusText.WAITING_TAIL}${cross}"
        DesktopNvidiaStatusKind.DIRECT -> "${lead}${separator}${DesktopNvidiaStatusText.DIRECT_TAIL}${cross}"
        DesktopNvidiaStatusKind.OVER_BUDGET -> "${lead}${separator}${DesktopNvidiaStatusText.OVER_BUDGET_TAIL}${cross}"
        DesktopNvidiaStatusKind.PENDING ->
            "${lead}${separator}${if (sharedCore) DesktopNvidiaStatusText.PENDING_TAIL else DesktopNvidiaStatusText.DRIVER_NOT_ACTIVE_TAIL}${cross}"
        DesktopNvidiaStatusKind.SUBMITTED ->
            "${lead}${separator}${if (sharedCore) DesktopNvidiaStatusText.SUBMITTED_TAIL else DesktopNvidiaStatusText.DRIVER_NOT_ACTIVE_TAIL}${cross}"
        DesktopNvidiaStatusKind.PROOF_DELAYED ->
            "${lead}${separator}${if (sharedCore) DesktopNvidiaStatusText.PROOF_DELAYED_TAIL else DesktopNvidiaStatusText.DRIVER_NOT_ACTIVE_TAIL}${cross}"
        DesktopNvidiaStatusKind.ACTIVE -> {
            if (sharedCore) {
                val sizes = input.inputWidth > 0 && input.inputHeight > 0 && input.outputWidth > 0 && input.outputHeight > 0
                val dims = if (sizes) {
                    "（${input.inputWidth}×${input.inputHeight} → ${input.outputWidth}×${input.outputHeight}）"
                } else {
                    ""
                }
                val hdr = if (input.hdrPresented) DesktopNvidiaStatusText.ACTIVE_HDR_SUFFIX else ""
                "${lead}${separator}${DesktopNvidiaStatusText.ACTIVE_VEYRA_TAIL}${dims}${hdr}${cross}"
            } else {
                "${lead}${separator}${DesktopNvidiaStatusText.ACTIVE_DRIVER_TAIL}${cross}"
            }
        }
        DesktopNvidiaStatusKind.STEPPED_DOWN ->
            "${lead}${separator}${DesktopNvidiaStatusText.steppedDownTail(input.qualityLevel ?: 1)}${cross}"
        DesktopNvidiaStatusKind.GUARD_BYPASS -> DesktopNvidiaStatusText.GUARD_BYPASS
        DesktopNvidiaStatusKind.PAUSED_GAME -> DesktopNvidiaStatusText.PAUSED_GAME
        DesktopNvidiaStatusKind.PAUSED_BATTERY -> DesktopNvidiaStatusText.PAUSED_BATTERY
        DesktopNvidiaStatusKind.PAUSED_REMOTE -> DesktopNvidiaStatusText.PAUSED_REMOTE
        DesktopNvidiaStatusKind.FAILED_REMEMBERED -> "${lead}${separator}${DesktopNvidiaStatusText.FAILED_REMEMBERED_TAIL}"
        DesktopNvidiaStatusKind.FAILED_THIS_VIDEO -> "${lead}${separator}${DesktopNvidiaStatusText.FAILED_THIS_VIDEO_TAIL}"
        DesktopNvidiaStatusKind.FAILED_EXTENDED_RANGE -> DesktopNvidiaStatusText.FAILED_EXTENDED_RANGE
        DesktopNvidiaStatusKind.COLOR_UNSUPPORTED -> DesktopNvidiaStatusText.COLOR_UNSUPPORTED
        DesktopNvidiaStatusKind.LUID_MISMATCH -> DesktopNvidiaStatusText.LUID_MISMATCH
        DesktopNvidiaStatusKind.LUID_UNKNOWN -> DesktopNvidiaStatusText.LUID_UNKNOWN
        DesktopNvidiaStatusKind.NON_D3D11_DECODE -> DesktopNvidiaStatusText.NON_D3D11_DECODE
    }
}

/**
 * The line for an activation state decided before the pipeline runs, or null for Legacy (today's
 * text stays) and Allowed (the live pipeline supplies the line).
 */
internal fun desktopNvidiaActivationStatusLine(
    state: DesktopNvidiaActivationState,
    route: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,
    tierWordEnabled: Boolean = TIER_WORD_ENABLED,
): String? {
    val input: DesktopNvidiaStatusInput = when (state) {
        is DesktopNvidiaActivationState.Legacy -> return null
        is DesktopNvidiaActivationState.Allowed -> return null
        is DesktopNvidiaActivationState.Off ->
            DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.OFF, state.gpuDescription, route)
        is DesktopNvidiaActivationState.Probing ->
            DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.PROBING, route = route)
        is DesktopNvidiaActivationState.NoNvidia ->
            DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.NO_NVIDIA, route = route)
        is DesktopNvidiaActivationState.Unsupported -> DesktopNvidiaStatusInput(
            DesktopNvidiaStatusKind.UNSUPPORTED, state.gpu?.description, route, unsupportedReason = state.reason,
        )
        is DesktopNvidiaActivationState.Unidentified ->
            DesktopNvidiaStatusInput(DesktopNvidiaStatusKind.UNIDENTIFIED, route = route)
        is DesktopNvidiaActivationState.Standby -> planInput(DesktopNvidiaStatusKind.STANDBY, state.plan, route)
        is DesktopNvidiaActivationState.PausedGame -> planInput(DesktopNvidiaStatusKind.PAUSED_GAME, state.plan, route)
        is DesktopNvidiaActivationState.PausedBattery ->
            planInput(DesktopNvidiaStatusKind.PAUSED_BATTERY, state.plan, route)
        is DesktopNvidiaActivationState.PausedRemote ->
            planInput(DesktopNvidiaStatusKind.PAUSED_REMOTE, state.plan, route)
        is DesktopNvidiaActivationState.FailedRemembered ->
            planInput(DesktopNvidiaStatusKind.FAILED_REMEMBERED, state.plan, route)
        is DesktopNvidiaActivationState.FailedThisVideo ->
            planInput(DesktopNvidiaStatusKind.FAILED_THIS_VIDEO, state.plan, route)
        is DesktopNvidiaActivationState.FailedExtendedRange ->
            planInput(DesktopNvidiaStatusKind.FAILED_EXTENDED_RANGE, state.plan, route)
    }
    return desktopNvidiaStatusLine(input, tierWordEnabled)
}

private fun planInput(kind: DesktopNvidiaStatusKind, plan: NvidiaPlan, route: NvidiaVideoBackend): DesktopNvidiaStatusInput =
    DesktopNvidiaStatusInput(
        kind = kind,
        gpuDescription = plan.gpuDescription,
        route = route,
        tier = plan.tier,
        crossAdapter = plan.crossAdapter,
    )
