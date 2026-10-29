# TON-1014：全厂商视频能力矩阵与实施交接

核查日期：2026-10-28。状态：官方能力调研材料；此表不代表 Windows 实测结果，运行时能力以探针与实测为准。

目标背后的目标：朋友安装后流畅播放，增强失败时自动恢复安全设置，不需要理解参数。

可度量目标：三家显卡能力逐格有出处；实现阶段以至少 12 种机器的纯函数测试、两套 Windows 的检测 JSON 与真实自测日志验收。

原始材料与工具：NVIDIA NVDEC 文档和驱动说明、AMD AMF 官方支持表、Intel oneVPL 表、仓库固定 mpv 源码、Microsoft Windows API；只读代码检索、GitHub API 与官方网页。

外部验证：验收者随机抽 5 格独立核对；能力声明必须再经目标机器查询与样片验证。本文没有把厂商标称能力当成实测。

## 阅读约定

- AVC 指 4:2:0 8-bit；HEVC 指 Main/Main10；AV1 指 Main 4:2:0 8/10-bit。不是任意 profile、分辨率、帧率均可用。
- “支持”是来源声明；“候选”是存在调用接口，仍需验证；“未知”是本次没有找到可依赖的确切白名单或驱动下限。未知不能自动变成支持，也不能写成硬件不支持。
- NVIDIA 的 GTX、Intel UHD、AMD 核显都跨多个架构，不能仅用商品名分组。下面明确代表型号，未列出的型号保持未知。
- 驱动功能的首次发布版本不等于每个后来上市 SKU 的最低驱动；启动探针必须记录完整四段 Windows 驱动版本及来源，再做厂商特定转换。
- 每格的链接是官方材料或 mpv 源码。未知格链接到本次审阅的材料，说明核查边界，并不表示该材料证明“不支持”。

## NVIDIA

| 能力 | GTX 1060（Pascal；其余 GTX 不一概而论） | RTX 20（Turing） | RTX 30（GA10x） | RTX 40（Ada） | RTX 50（Blackwell） |
|---|---|---|---|---|---|
| AVC | [支持][nvdec] | [支持][nvdec] | [支持][nvdec] | [支持][nvdec] | [支持][nvdec] |
| HEVC Main/Main10 | [支持][nvdec] | [支持][nvdec] | [支持][nvdec] | [支持][nvdec] | [支持][nvdec] |
| AV1 Main | [不支持][nvdec] | [不支持][nvdec] | [支持；不推广至 GA100][nvdec] | [支持][nvdec] | [支持][nvdec] |
| Windows mpv hwdec 候选 | [d3d11va；也可评估 nvdec][mpv-options] | [d3d11va / nvdec][mpv-options] | [d3d11va / nvdec][mpv-options] | [d3d11va / nvdec][mpv-options] | [d3d11va / nvdec][mpv-options] |
| RTX VSR | [非 RTX；不满足 RTX 条件][nv-faq] | [支持；mpv scaling-mode=nvidia][nv-vsr15] | [支持；同接口][nv-vsr15] | [支持；同接口][nv-vsr15] | [RTX Video 候选；实际扩展需查询][nv-faq] |
| SDR→HDR | [非 RTX；不满足 RTX 条件][nv-faq] | [RTX Video HDR 候选][nv-faq] | [RTX Video HDR 候选][nv-faq] | [RTX Video HDR 候选][nv-faq] | [RTX Video HDR 候选][nv-faq] |
| AVC/HEVC/AV1 的 d3d11va 最低驱动 | [未知][nvdec] | [未知][nvdec] | [未知][nvdec] | [未知][nvdec] | [未知；5080 硬件首发驱动见下][nv-572] |
| VSR 最低功能驱动 | [不适用][nv-faq] | [545.84 + VSR 1.5][nv-545] | [531.18 为功能首发；具体 SKU 另查][nv-531] | [531.18 为功能首发；具体 SKU 另查][nv-531] | [未知；5080/5090 支持从 572.16 起][nv-572] |
| Video HDR 最低功能驱动 | [不适用][nv-faq] | [551.23][nv-551] | [551.23][nv-551] | [551.23；具体 SKU 另查][nv-551] | [未知；不能把 551.23 用作 50 系列驱动下限][nv-572] |

mpv 固定源码有 `nvidia-true-hdr`，并对原生 HDR 输入避免再次 SDR→HDR；它与 VSR 是不同的开关。[固定源码][mpv-vpp]。HDR 显示器还必须已启用 Windows HDR；本任务不替用户切系统开关。[NVIDIA FAQ][nv-faq]。

## AMD

| 能力 | RDNA1（RX 5000 / VCN2） | RDNA2（RX 6600 等 / VCN3） | RDNA3（RX 7000 / VCN4） | RDNA4（RX 9000 / VCN5） | AMD 核显 |
|---|---|---|---|---|---|
| AVC | [支持][amd-hw] | [支持][amd-hw] | [支持][amd-hw] | [支持][amd-hw] | [VCN2/3/4/5 支持；其他未知][amd-hw] |
| HEVC Main/Main10 | [支持][amd-hw] | [支持][amd-hw] | [支持][amd-hw] | [支持][amd-hw] | [VCN2/3/4/5 支持；其他未知][amd-hw] |
| AV1 Main | [不支持][amd-hw] | [VCN3 支持][amd-hw]；[6500 XT 例外不支持][amd-6500] | [支持][amd-hw] | [支持][amd-hw] | [VCN2 无；VCN3/4/5 有；按具体 APU 查][amd-hw] |
| Windows mpv hwdec 候选 | [d3d11va][mpv-options] | [d3d11va][mpv-options] | [d3d11va][mpv-options] | [d3d11va][mpv-options] | [d3d11va；逐设备查][mpv-options] |
| 驱动视频超分 | [未知][amd-upscale] | [未知][amd-upscale] | [RX7000 桌面卡有 AMD Video Upscaling][amd-upscale] | [此材料未给 RDNA4 白名单：未知][amd-upscale] | [未知][amd-upscale] |
| AMF/应用级替代 | [HQScaler 候选；逐设备未知][amf] | [HQScaler 候选；逐设备未知][amf] | [HQScaler 候选；逐设备未知][amf] | [HQScaler 候选；逐设备未知][amf] | [HQScaler 候选；逐设备未知][amf] |
| SDR→HDR 厂商对应方案 | [未知][amf] | [未知][amf] | [未知][amf] | [未知][amf] | [未知][amf] |
| 硬解最低 Windows 驱动 | [未知][amd-hw] | [未知][amd-hw] | [未知][amd-hw] | [未知][amd-hw] | [未知][amd-hw] |
| 超分最低驱动 | [逐 GPU 未知；AMF SDK 见说明][amf] | [逐 GPU 未知][amf] | [驱动功能 24.1.1；不等于 mpv 已接入][amd-upscale] | [未知][amf] | [未知][amf] |
| SDR→HDR 最低驱动 | [未知][amf] | [未知][amf] | [未知][amf] | [未知][amf] | [未知][amf] |

AMD 官方驱动视频超分需要在控制面板启用，24.1.1 的材料还有单屏限制；不满足本卡“只读、不可修改系统设置”的自动启用条件。[24.1.1 说明][amd-upscale]。AMF 的 HQScaler 包含 FSR，SDK 1.4.24 对应 22.3.1；这只是 SDK 发布依赖，不能推导每个 GPU 的最低版本或 mpv 能力。[AMF 版本记录][amf]。当前固定 `d3d11vpp` 选项只有 standard/intel/nvidia，没有 `scaling-mode=amd`。[固定源码][mpv-vpp]。通用 shader 可作为后续经过测量的候选，不能标为厂商 AI 超分；本轮没有验证任何 shader。

## Intel

| 能力 | UHD 630（9 代 Core） | UHD 730/770（12/13 代 Core） | Iris Xe（11 代 Core） | Arc A 系列（含 A770） | Core Ultra（Arc GPU） |
|---|---|---|---|---|---|
| AVC | [支持][intel-vpl] | [支持][intel-vpl] | [支持][intel-vpl] | [支持][intel-arc] | [支持][intel-arc] |
| HEVC Main/Main10 | [支持][intel-vpl] | [支持][intel-vpl] | [支持][intel-vpl] | [支持][intel-arc] | [支持][intel-arc] |
| AV1 Main | [不支持][intel-vpl] | [支持][intel-vpl] | [支持][intel-vpl] | [支持][intel-arc] | [支持][intel-arc] |
| Windows mpv hwdec 候选 | [d3d11va][mpv-options] | [d3d11va][mpv-options] | [d3d11va][mpv-options] | [d3d11va][mpv-options] | [d3d11va][intel-ultra] |
| Intel VPE 超分 | [具体支持未知；接口存在][mpv-vpp] | [具体支持未知；接口存在][mpv-vpp] | [具体支持未知；接口存在][mpv-vpp] | [具体支持未知；接口存在][mpv-vpp] | [具体支持未知；接口存在][mpv-vpp] |
| SDR→HDR 厂商方案 | [未知][intel-vpl] | [未知][intel-vpl] | [未知][intel-vpl] | [未知][intel-arc] | [未知][intel-ultra] |
| 硬解最低 Windows 驱动 | [未知][intel-vpl] | [未知][intel-vpl] | [未知][intel-vpl] | [未知][intel-arc] | [未知][intel-ultra] |
| VPE 超分最低驱动 | [未知][mpv-vpp] | [未知][mpv-vpp] | [未知][mpv-vpp] | [未知][mpv-vpp] | [未知][mpv-vpp] |
| SDR→HDR 最低驱动 | [未知][intel-vpl] | [未知][intel-vpl] | [未知][intel-vpl] | [未知][intel-arc] | [未知][intel-ultra] |

Intel VPE 的 `scaling-mode=intel` 在固定 mpv 源码中依次调用版本、预处理模式、VSR 扩展。接口存在不能证明任意 Intel GPU 的驱动会接受或真的执行，必须保留 HRESULT 与后续帧验证结果。[固定源码][mpv-vpp]。Intel 官方还明确设备配置可能影响可用编码。[oneVPL][intel-vpl]。

## 会改变实现的核查结果

1. **离屏测试的证据边界**：NVIDIA 官方说明隐藏或最小化视频不会激活 RTX Video。约 10 秒后台测试可验证解码、过滤器初始化和掉帧，不能仅凭没有错误就显示“已开启超分与 HDR”。需要在后续真实播放中沿用仓库已有的帧呈现证据门禁，成功后才更新状态。这里是工程推断，离屏 API 能否提供其他强证明尚未实测。[NVIDIA FAQ][nv-faq]。
2. **双显卡**：窗口显示输出卡与 mpv 渲染卡必须分别记录。不能把独显枚举成功当成该窗口正在使用独显，更不能把显示卡 LUID 当成渲染卡 LUID。mpv 的 D3D11 adapter 选项会影响依赖 D3D11 设备的解码。[mpv options][mpv-options]。
3. **硬解必须带维度**：驱动可返回某个 profile，但目标分辨率、输出纹理格式或配置仍可能不支持。查询要走 profile→format→config，再用样片实际解码。[Microsoft 解码流程][ms-decode]。
4. **独占音频**：按具体 endpoint、PCM 格式查询 exclusive 的 `IsFormatSupported`，S_OK 才表示格式支持；这不保证当时可以成功独占，仍需保留共享模式回退。只读检测不调用 Initialize/Start。[Microsoft 音频 API][ms-audio]。

## 仓库现状（fc71094957fe862d515ceac54c60b6030a1fd1fc）

- `desktop/src/main/java/com/bilipai/desktop/player/DesktopWindowsDxgiAdapters.java`：`probeDetailed()` 已枚举全厂商；附加 outputCount 只对 NVIDIA 做；`umdVersionFor()` 是按 LUID 查询的现成入口。扩展它，避免另起一套 DXGI 枚举。
- `desktop/src/main/kotlin/com/bilipai/desktop/nvidia/DesktopNvidiaGpuCapability.kt`：`NvidiaProbeInput.adapters` 已含所有厂商，且有 `displayAdapterLuidHex`；不需要第二份“显卡列表”。
- `DesktopNvidiaCapabilityMonitor.kt`：系统 probe 只在 NVIDIA 主卡选择后读取其驱动；目前没有填充窗口显示卡 LUID。须保留原有 physical/LUID 门禁。
- `DesktopWindowsGpuProbe.kt`：已具备 DisplayConfig 的只读调用与 Windows ABI 保护，可扩展显示器拓扑、刷新率、DPI、HDR 状态及 CPU/内存事实。
- `DesktopNvidiaHealthStore.kt` / `DesktopNvidiaAdaptiveGuard.kt`：复用现有失败记忆与持续掉帧原则；泛化数据不能伪装成 NVIDIA 呈现证明。
- `desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnostics.kt`：已有有界日志、脱敏、串行写入和用户主动导出；`buildExport()` 当前未收集硬件优化快照。新增结构化快照要接这里，并沿用 `diagnostic-share` 的现有用户导出入口。

实现与调用契约见 Kotlin hardware 目录的 README.md。

[nvdec]: https://docs.nvidia.com/video-technologies/video-codec-sdk/13.0/nvdec-application-note/index.html#nvdec-capabilities
[nv-faq]: https://nvidia.custhelp.com/app/answers/detail/a_id/5448
[nv-vsr15]: https://blogs.nvidia.com/blog/rtx-video-super-resolution-ai-obs-broadcast/
[nv-531]: https://www.nvidia.com/download/driverResults.aspx/199990/en-us/l/
[nv-545]: https://www.nvidia.com/en-us/drivers/details/212897/
[nv-551]: https://www.nvidia.com/en-sg/geforce/news/geforce-rtx-4070-ti-super-rtx-video-hdr-game-ready-driver/
[nv-572]: https://www.nvidia.com/en-us/geforce/news/geforce-rtx-5090-5080-dlss-4-game-ready-driver/
[mpv-options]: https://github.com/mpv-player/mpv/blob/69e63f425a531f814431fba12750bdb3721357f2/DOCS/man/options.rst
[mpv-vpp]: https://github.com/mpv-player/mpv/blob/69e63f425a531f814431fba12750bdb3721357f2/video/filter/vf_d3d11vpp.c
[amd-hw]: https://github.com/GPUOpen-LibrariesAndSDKs/AMF/wiki/GPU%20and%20APU%20HW%20Features%20and%20Support
[amd-6500]: https://www.amd.com/en/products/graphics/desktops/radeon/6000-series/amd-radeon-rx-6500-xt.html
[amd-upscale]: https://www.amd.com/en/resources/support-articles/release-notes/RN-RAD-WIN-24-1-1.html
[amf]: https://github.com/GPUOpen-LibrariesAndSDKs/AMF/blob/master/README.md
[intel-vpl]: https://www.intel.com/content/www/us/en/docs/onevpl/developer-reference-media-intel-hardware/1-1/overview.html
[intel-arc]: https://www.intel.com/content/www/us/en/support/articles/000098345/graphics.html
[intel-ultra]: https://edc.intel.com/content/www/us/en/design/products/platforms/details/meteor-lake-u-p/core-ultra-processor-datasheet-volume-1-of-2/003/hardware-accelerated-video-decode/
[ms-decode]: https://github.com/MicrosoftDocs/win32/blob/docs/desktop-src/medfound/supporting-direct3d-11-video-decoding-in-media-foundation.md
[ms-audio]: https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-isformatsupported
[ms-dpi]: https://learn.microsoft.com/en-us/windows/win32/hidpi/high-dpi-desktop-application-development-on-windows
