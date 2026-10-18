package com.bilipai.desktop.nvidia

// Optional residual mpv scaler profile for the strongest tiers (design section 12, slice S7).
//
// After the NVIDIA filter has run, mpv may still resample the frame to the window. On a machine that
// has the headroom, a stronger residual scaler can help. This file holds only the option table, the
// rule that picks a table for a capability verdict, and a non-throwing setter. It makes no native
// call of its own: the player hands in the one function that sets an option, and it passes it only
// before the core is initialised.
//
// The profile is off. Only a hardware run can show that the display-match proof still arrives with
// these options set (design section 17, item 11), so the switch below stays false until then and the
// player sets nothing today.
//
// The tables never contain: the shader-list option (it is a gate bit of the presentation proof), any
// frame-blending or display-sync timing option, debanding, dithering, a named quality profile (it is
// unconfirmed while config loading is off), a filter after the NVIDIA filter, or a swap chain
// change. The setter enforces this: it passes on only the exact rows of the two tables, so an edit
// elsewhere cannot add one by accident.
//
// Nothing here reads or writes the enhancement state or any presentation flag.

/**
 * Root switch for the residual scaler profile. Stays false until a hardware run shows that the
 * display-match proof still arrives with the profile set.
 */
internal const val MPV_SCALER_PROFILE_ENABLED: Boolean = false

/** One mpv option as text, set before the core is initialised. */
internal data class NvidiaMpvScalerOption(val name: String, val value: String)

// MPV-SCALER-TABLE-BEGIN
/** The HQ profile for the HIGH tier. */
internal val NVIDIA_MPV_SCALER_HQ_OPTIONS: List<NvidiaMpvScalerOption> = listOf(
    NvidiaMpvScalerOption("scale", "ewa_lanczossharp"),
    NvidiaMpvScalerOption("cscale", "ewa_lanczossharp"),
    NvidiaMpvScalerOption("scale-antiring", "0.6"),
    NvidiaMpvScalerOption("dscale", "mitchell"),
)

/** The HQ-ultra profile for the ULTRA tier: the HQ profile with the stronger downscaler. */
internal val NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS: List<NvidiaMpvScalerOption> = listOf(
    NvidiaMpvScalerOption("scale", "ewa_lanczossharp"),
    NvidiaMpvScalerOption("cscale", "ewa_lanczossharp"),
    NvidiaMpvScalerOption("scale-antiring", "0.6"),
    NvidiaMpvScalerOption("dscale", "ewa_lanczos"),
)
// MPV-SCALER-TABLE-END

/** Every option the setter may ever pass on: exactly the rows of the two tables and nothing else. */
internal val NVIDIA_MPV_SCALER_ALLOWED_ROWS: Set<NvidiaMpvScalerOption> =
    (NVIDIA_MPV_SCALER_HQ_OPTIONS + NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS).toSet()

/** The table of one scaler class. The default class sets nothing. */
internal fun nvidiaMpvScalerOptions(scalerClass: NvidiaMpvScalerClass): List<NvidiaMpvScalerOption> =
    when (scalerClass) {
        NvidiaMpvScalerClass.HQ_ULTRA -> NVIDIA_MPV_SCALER_HQ_ULTRA_OPTIONS
        NvidiaMpvScalerClass.HQ -> NVIDIA_MPV_SCALER_HQ_OPTIONS
        NvidiaMpvScalerClass.MPV_DEFAULT -> emptyList()
    }

/**
 * The scaler class the tier table gives the verdict's tier (ULTRA and HIGH only). Every verdict
 * other than a capable GPU, including an unfinished or failed probe, keeps the mpv defaults.
 */
internal fun nvidiaMpvScalerClassFor(capability: NvidiaCapability): NvidiaMpvScalerClass =
    if (capability is NvidiaCapability.Capable) {
        nvidiaTierProfile(capability.tier).mpvScaler
    } else {
        NvidiaMpvScalerClass.MPV_DEFAULT
    }

/**
 * The options to set for this verdict. Empty unless the switch is on and the verdict is a capable
 * ULTRA or HIGH tier. The player calls this without the switch argument, so only the constant
 * above can turn the profile on.
 */
internal fun nvidiaMpvScalerOptionsFor(
    capability: NvidiaCapability,
    enabled: Boolean = MPV_SCALER_PROFILE_ENABLED,
): List<NvidiaMpvScalerOption> =
    if (enabled) nvidiaMpvScalerOptions(nvidiaMpvScalerClassFor(capability)) else emptyList()

/**
 * What one application of the profile did. A diagnostic only: it never decides anything, and it
 * carries option names, never values.
 */
internal data class NvidiaMpvScalerApplyResult(
    val requested: Int,
    val accepted: Int,
    /** Options the core rejected, that raised, or that the setter refused to pass on. */
    val rejectedNames: List<String>,
)

/**
 * Sets each option through setOption (the player's own option setter: a negative code is a
 * rejection) and never throws. A row outside the two tables is refused without calling setOption.
 * A rejected or failing option is skipped and the rest are still set, so a partly applied profile
 * is possible and harmless. This is deliberately not the throwing loop the required startup
 * options use: the profile is optional and must never fail a session.
 */
internal fun applyNvidiaMpvScalerOptions(
    options: List<NvidiaMpvScalerOption>,
    setOption: (String, String) -> Int,
): NvidiaMpvScalerApplyResult {
    var accepted = 0
    val rejected = ArrayList<String>()
    for (option in options) {
        if (option !in NVIDIA_MPV_SCALER_ALLOWED_ROWS) {
            rejected.add(option.name)
            continue
        }
        val code = try {
            setOption(option.name, option.value)
        } catch (_: Throwable) {
            -1
        }
        if (code >= 0) {
            accepted++
        } else {
            rejected.add(option.name)
        }
    }
    return NvidiaMpvScalerApplyResult(requested = options.size, accepted = accepted, rejectedNames = rejected)
}
