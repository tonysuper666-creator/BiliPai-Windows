# VERIFY desktop/integration-263-claude-main@c985a0386（Root 切片 #17 第一步：无带宽估计不再挡住相关列表）

环境：Linux，Temurin JDK 21，Gradle 9.5.0，--no-daemon --max-workers=1，-Xmx6g。使用 grokbot/linux-bootstrap 的两个桩文件（原生诊断钉 8d256ff3…；JS worker 全零桩，只用于编译），在隔离 worktree 里，从干净构建开始。

## 结果
- **compileKotlin / compileTestKotlin：BUILD 成功，0 错误**（test 任务失败只是因为下面 3 个已知 JS worker 类）。
- VERIFY 指定的 7 个类：

  | 类 | 结果 |
  |---|---|
  | DesktopOriginalVideoRelatedNetworkObservationTest（新增） | 4/4 |
  | DesktopOriginalVideoMetadataRelatedViewModelTest | 8/8 |
  | DesktopWindowsRelatedVideosRegressionTest | 10/10 |
  | DesktopBangumiDefaultQualityTest | 7/7 |
  | DesktopOriginalVideoCdnSelectionAssemblyTest | initializationError |
  | DesktopOriginalPortraitMetadataPreparationTest | initializationError |
  | DesktopOriginalSponsorPresentationAssemblyTest | initializationError |

  后 3 个是 Linux 上已知的 JS worker 初始化失败（需要真实的 JS worker 运行时，Linux 只有全零桩），dd37c7abc 和 89a0aee85 上也一样，**不是回归，但在 Linux 上无法验证**。
- **额外加跑**：除了列出的类，还有 9 个测试类的夹具同样构造了 `DesktopOriginalVideoRelatedNetworkObservation(..., downstreamBandwidthKbps = null)` 或实现了 relatedNetworkObservation，语义同样被改变，所以一并跑了，**全部通过，共 70 项**：

  | 类 | 结果 |
  |---|---|
  | DesktopOriginalVideoFallbackCauseViewModelTest | 3/3 |
  | DesktopBangumiVideoCodecRecoveryAssemblyTest | 8/8 |
  | DesktopBangumiPremiumAudioRecoveryAssemblyTest | 12/12 |
  | DesktopBangumiCourseDownloadOwnershipTest | 10/10 |
  | DesktopBangumiBasSourceAdmissionAssemblyTest | 10/10 |
  | DesktopBangumiAuthorizationRecaptureAssemblyTest | 7/7 |
  | DesktopWindowsSubtitlePreferenceStateTest | 7/7 |
  | DesktopOriginalVideoAuthorizationRecaptureViewModelTest | 2/2 |
  | DesktopSettingsShareMirrorTest | 11/11 |

## 重点：有没有依赖“相关列表要等播放就绪才发请求”的断言
- 这 3 个 JS worker 类的夹具都是 Wi-Fi、有互联网、非移动网络、带宽 null。c985 之前被判为“受限”（要等播放余量），c985 之后相关列表与视频并行加载。所以如果有顺序依赖，就会出在它们身上。
- 静态审读（不能运行）：
  - CdnSelection 第 261-293 行（holdRelated 夹具）先 `awaitLoaded`，它只等 uiState 和 native 当前请求，不等相关列表。之后才用 8 秒超时 `relatedEntered.await()`，并用 relatedRelease 闩锁控制释放。相关读取早进入或晚进入，这段流程都成立。
  - 三个类的 `load()` 都是“加载 → actor.loaded → 等运行时代号 → 等 relatedLoadState == READY”。它们等的是“最终就绪”，不是“就绪以前不能发”。
  - 没有找到“播放就绪之前相关请求次数必须是 0”这一类断言。
- RelatedVideosRegressionTest 和 SettingsShareMirrorTest 的 relatedNetworkObservation 是 `error(...)`（说明那条路径不应走到），两者都通过，说明这次改动没有让它们走进早期调度。
- 结论：能在 Linux 上跑的全部通过；跑不了的 3 个类，静态上看不出顺序依赖，但**必须以 Windows 云端结果为准**。

## 证据分类
- Linux 编译：是（0 错误）。
- JVM：Linux 上 4 + 9 个类，共 99 项通过；3 个类因已知 JS worker 原因无法运行。
- Python：本条目不要求，没跑。
- 未验证：3 个 JS worker 类的实际运行；真实 Windows 网络（WWAN 判定、无互联网）；UI 上相关列表出现的时机。
