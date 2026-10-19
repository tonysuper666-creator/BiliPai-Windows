# T7 集成体检（只汇报，不改代码）

**对象：desktop/integration-263@dd37c7abc**，不是 r1-test-currency@3d0deb5cf。依据 T7.md：“若 Root 之后在 VERIFY.md 里给了更新的提交，以 VERIFY.md 为准”。dd37c7abc 包含 3d0deb5cf 中 Root 采纳的 6 个测试提交、R1 Veyra 重新钉扎、符号闭包检查，以及 T8a/T8b。
命令行沿用 T0（grokbot/linux-bootstrap 的 bootstrap/gradle.sh、test.sh、python-test.sh）。时间 2026-10-19 02:29–02:55 UTC。

## 1. 编译与警告
`rm -rf desktop/build` + make-stubs + `compileKotlin compileJava compileTestKotlin compileTestJava --no-build-cache`：**BUILD SUCCESSFUL，6m26s，0 错误**。

| 线 | compileKotlin 警告 |
|---|---|
| 256 基线 | 455 |
| 261 修复线 | 457 |
| NVIDIA 线 | 455 |
| **dd37c7abc（本次）** | **457**（compileTestKotlin 98） |

457 与 261 修复线一致（评论修复线带入的 +2）。合入 NVIDIA、Veyra、T8 后没有新增 main 警告。

## 2. JVM（同一次 test 运行，--tests 逐类）
| 组 | 结果 |
|---|---|
| com.bilipai.desktop.nvidia.* 全部（8 类）+ player 的 5 类 + plugins.DesktopVideoEnhancementConfigurationTest | 14 类全过 |
| 任务 3 的评论相关 23 类 | 23 类全过 |
| 合计 | **37/37 类，566 测试，0 失败，0 跳过** |

（同一次运行还包含 VERIFY 要求的 danmaku 全包 196/196、ui 全包 1347 个测试中仅 3 个已知 JS worker 环境失败，见 grokbot/verify-dd37c7abc。）

## 3. Python
先不带垫片运行，失败再用 `\\?\` 垫片重跑；任何一次都没有在仓库根下留下 `\\?\` 目录。

| 测试 | 结果 | 垫片 | 耗时 |
|---|---|---|---|
| desktop/tools/nvidia 全部 | 323 OK | 不需要 | 1 s |
| test_original_player_nvidia_slot | 3 OK | **需要**（无垫片时 FileNotFoundError `\\?\/…/upstream-sources.json`） | 2 s |
| test_extract_video_enhancement | 7 OK | 不需要 | <1 s |
| test_windows_audio_output_focus_sources | 9 OK | 不需要 | 1 s |
| test_rtx_identity_postfilters | 9 OK | 不需要 | <1 s |
| test_rtx_hdr_color（真实的，未替换） | **7 OK，R1 之后通过**（基线 1 失败/7） | 不需要 | <1 s |
| test_v046_comment_list_source | 26 OK | **需要**（无垫片时 extract-upstream-video-comment-ui.py 子进程 `\\?\` 路径错误） | 6 s |
| test_v046_comment_thread_source | 10 OK | 不需要 | 1 s |
| test_validate_source_cohort | 25 OK（skipped=1） | 不需要 | <1 s |
| test_package_source_qualification | 14 OK | 不需要 | <1 s |
| test_sync_upstream | 74 OK | 不需要 | 65 s |

## 4. 与已知基线失败对比
| 测试 | 基线 | 本次（垫片） | 差异 |
|---|---|---|---|
| test_rtx_hdr_color | 1 失败/7 | 7 OK | **已修复（R1）** |
| test_extract_upstream_brand_success_mount | 2 失败/3 | 2 失败/3（无垫片是 3 个路径错误） | 无变化 |
| test_extract_upstream_up_danmaku | 2 失败/10 | 2 失败/10 | 无变化 |
| test_extract_upstream_comment_renderer | 2 失败 + 1 错误/5 | 2 失败 + 1 错误/5 | 无变化 |

**新增失败：无。**
观察（未验证的推测）：up_danmaku 的失败名为 `…owner_last_stage_inverse_equals_full_generated_original…`，形态和 T10 已确认的“测试从最终输出直接撤销，没有先撤销后加的外层阶段”相似，可以作为后续只读分析的候选。

## 证据分类
| 类别 | 内容 |
|---|---|
| Linux 编译 | 已验证（无缓存，0 错误，警告 457/98） |
| Python | 已验证（上表；需要垫片的已注明） |
| JVM（Linux） | 已验证：37 类 566/566 |
| 未验证 | Windows 云验证、真机、NVIDIA 硬件、音频、UI、安装器 |

没有修改任何代码。
