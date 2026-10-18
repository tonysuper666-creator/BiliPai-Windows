# Task 5 — 256 基线全量 JVM 测试扫描（只报告，未修复任何东西）

- 分支：本地 `grokbot/full-jvm-scan-256`，从 origin/desktop/source-validation-package-256-comment-geometry-compatibility（610218f8b）切出。代码和测试一律没改。
- 环境：Linux（Debian 13），Temurin 21.0.12.1，Python 3.13。沿用 Task 1 的变通：仓库外的 `\\?\` 路径垫片（PYTHON_EXECUTABLE）；两个 build 目录桩文件（诊断 DLL 用审核 pin，JS worker 用**全零哈希**）；命令行参数 `-Dorg.gradle.jvmargs=-Xmx6g -Xss16m -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m`；`--no-daemon --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process`。
- 排除的任务：`-x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker`，另加 `-x prepareJsWorkerResources`。最后这个必须排除：它是一个 Sync 任务，把 Windows JS worker 的产物同步进 desktop/resources/common/js-engine，可它依赖的 prepareJsWorker 已被排除，跑起来只能同步一个不存在的产物。
- 第一次尝试作废：build/generated 里还留着 260/261 的生成物（例如 dynamic-reply/test），256 编译不过。删掉 desktop/build 后重新开始，所以本报告的结果来自干净的 build 目录。
- 分 8 批跑 Gradle `test` 任务（用 --tests 按包过滤），同一时间只跑一个 Gradle；每批结束后把 build/test-results 的 XML 复制到 /workspace/g-logs/t5/<批次>/。

## 总表
| 批次 | 过滤 | 类 | 用例 | 通过 | 失败 | 跳过 |
|---|---|---|---|---|---|---|
| android-upstream | com.android.purebilibili.* + su.litvak.* | 132 | 1041 | 1041 | 0 | 0 |
| data | com.bilipai.desktop.data.* | 45 | 375 | 373 | 2 | 0 |
| desktop-misc1 | danmaku/settings/plugins(.js)/appearance | 75 | 483 | 473 | 10 | 0 |
| desktop-misc2 | update/download/brand/audio/backup/cast/network/diagnostics | 34 | 322 | 310 | 7 | 5 |
| desktop-root | com.bilipai.desktop 根包 6 个类 | 6 | 30 | 30 | 0 | 0 |
| leftover | com.bytedance.* + com.bilipai.desktop.privacyfixture.* | 2 | 9 | 9 | 0 | 0 |
| player | com.bilipai.desktop.player.* | 42 | 378 | 378 | 0 | 0 |
| ui | com.bilipai.desktop.ui.* | 187 | 1346 | 1337 | 9 | 0 |
| **合计** | | **523** | **3984** | **3951** | **28** | **5** |

- 计划内的类（`.github/windows-source-validation-plan.json`，139 个类、1245 项）：**全部执行，每个类的用例数都和计划一致，0 失败、0 跳过。**
- 5 个跳过都是 Windows 专用的 assumption：DesktopDiagnosticsTest 3 个（Windows junction、独占句柄），DesktopUpdaterSafetyTest 2 个（Windows 短路径别名、目录 junction）。
- `test` 任务没跑的 4 个类（不算进上表）：NativeDownloadMuxTest（标签 native-mux）、DesktopJsPluginIntegrationTest（标签 js-worker）、DesktopUpdaterIntegrationTest（标签 packaged-updater）。这三个被 build.gradle.kts 里 `tasks.test` 的 excludeTags 排除，分别由 Windows 冒烟任务 nativeMuxSmoke / jsWorkerSmoke / updaterSmoke 执行，没跑。GoogleCastFixtureTest 只有 main()，没有 @Test，不是 JUnit 测试。
- 复测：确定性失败的类又单独跑了一遍，结果相同。只有 DesktopOriginalVideoCoverFileAdmissionTest 复测时通过，判为偶发（flaky）。

## 失败清单（28 项，按类别）
根因分析：由 Grok Build 只读分析（附件 task5-failure-analysis.md，含 file:line 证据和 git 历史），我抽查过 #1、#2、#3、#5、#8、#12，跟源码和历史一致。

### (a) Windows 专用 / 环境（9 项）
| 类 | 测试 | 原始信息 | 根因 |
|---|---|---|---|
| data.DesktopDynamicCacheTest | actualWindowsReplacementFailureKeepsDurableAndPublishedStateThenWriterRecovers | UnsupportedOperationException: NOSHARE_DELETE not supported | 测试第 209 行用了 Windows 独有的共享模式 ExtendedOpenOption.NOSHARE_DELETE，Linux 的 JDK 不支持；产品代码根本没执行到 |
| plugins.DesktopPreferenceReplacementRetryWindowsTest | 5 项（retryReevaluates…、permanentRealWindowsDenial…、capturedPlaybackRechecks…、actualDeniedHandleReleased…、retirementAfterActualFailedMove…） | IllegalStateException: Opt-in Windows native file test | hold() 第 33 行 `check(os.name 以 windows 开头)`，用的是 check 而不是 assume，所以在 Linux 上报失败而不是跳过 |
| ui.DesktopOriginalPortraitMetadataPreparationTest / DesktopOriginalSponsorPresentationAssemblyTest / DesktopOriginalVideoCdnSelectionAssemblyTest | initializationError（3 项） | IllegalArgumentException: JS worker resource path is invalid | 缺 Windows JS worker 资源（prepareJsWorker 被排除，哈希是全零桩），DesktopJsWorkerResources.kt:63 |

### (b) 跟平台无关的真实失败（12 项，可能是真 bug，需要人工确认）
| 类 | 测试 | 原始信息 | 根因判断 |
|---|---|---|---|
| danmaku.DesktopBasOverlayPublicationTest | 4 项（same-version recovery…、blocked old BAS worker…、a fresh binding…、rejected final presentation…） | Actual BAS publication did not reach <状态>; count=1/0 | 旧 BAS 发布只在 tick() 里退役；fb4db9959（10-14，v038 帧节奏移植）之后，tick 要等 canRenderOverlay（窗口可见且已解出视频帧）才执行。这个测试夹具两样都没有，在 Windows 上同样会失败。高置信 |
| download.DesktopDownloadBrandSuccessTest | restoredCompletedTaskDoesNotEmitAgain | NoSuchElementException: List is empty | 6da71260b（10-12）之后，close() 改成异步退役，返回时还没写盘、没释放 FileLock；同一个 JVM 里新开的 manager 拿不到锁（OverlappingFileLockException 被当成忙），任务列表初始为空。Windows 上也一样。高置信。另一种看法是“测试应该调用 shutdownForApplicationExit”，那就算 (c) |
| download.DesktopDownloadRefreshQualityTest | managerPersistenceExplicitlyStoresCreatedAt…、fallbackStreamUpdatesActualQuality…（2 项） | NoSuchElementException: List is empty | 同上 |
| ui.DesktopOriginalNativeRecoveryAdmissionTest | sameActualAttemptPublishesOneAacReplacement… | CancellationException: Accepted ordinary source/presenter retired | 恢复流程走到 MPV 的 `define-section`（7cb07b2fc，10-15 新增），内存桩对这个命令直接抛异常，结果 publishFailure 把下一次 prepareLegacyDash 退役了。高置信，不过触发点是测试桩不支持新命令，也可以算 (c) |
| ui.DesktopWindowsEarlyVideoDetailTest | fastUrlFailureWaitsForDelayedDetailCallbackBeforeTypedError；retiredOwnerEscapesCancellationWithoutPublishingLateDetail（2 项） | expected ViewInfo but was null；expected 0 but was 1 | 生成的 v034 并行加载：URL 失败会把整个 coroutineScope 连同还在等的详情请求一起取消；而且 onVideoInfo 在 assertOwned() 之前就调用了，已退役的 owner 照样会发布详情。中置信（如果契约是“原样照抄 v034”，就算 (c)） |
| ui.DesktopWindowsVideoStatisticsLeaseRegressionTest | acknowledgedSourceCopiesExactBvOnEdt… | Expected value to be true | 同样是 define-section：内存桩在 onLoadCommandAccepted 之前抛异常，consumed 一直是 false，请求 job 一取消，admit 就返回 false。另外它暴露了一个点：输入区设置失败时，源看起来仍是“已接受”状态。高置信 |
| ui.DesktopOriginalVideoCoverFileAdmissionTest | actualBindingRejectsRetiredCaptureAtFinalMove… | IOException: Failed to delete temp directory … | **偶发**，复测通过。manager.close() 是异步的，@TempDir 清理时目录还没删空。中置信 |

### (c) 过时测试，期望值没跟上（7 项）
| 类 | 测试 | 原始信息 | 根因 |
|---|---|---|---|
| data.DesktopCommunityRepositoryTest | requestParametersMatchUpstreamSpaceAndSearch | expected <20> but was <30>（第 124 行） | 固定上游 v041 SearchRepository 的 page_size 是 30（eaa4c484e，10-16）；测试写于 09-30 |
| danmaku.DanmakuTest | duplicate merging keeps the original timing and styles and can be disabled | expected <[0, 3, 2]> but was <[0]> | d1555e6d9（10-14）换成了固定的 v038 DanmakuMerger：按文本分组、15 秒窗口、不按颜色区分；测试还是按旧的 500ms 加颜色区分来写的 |
| update.DesktopUpdaterRegistryTransactionTest | 4 项（realBusyLock…、oldPreviousFallback…、registeredCanonical…、lateUpgradeCannot…） | NoSuchMethodException: ActiveInstallation.<init>(5×String) | 测试用反射找 5 个参数的构造函数；6da71260b 加了 downloadHandoffToken 字段，现在是 6 个参数 |
| ui.DesktopWindowsCommentSearchBindingTest | originalTimePagingOffsetsProgressAndTopDedupeAreConsumed | 期望 ps=20，实际是 30 | 固定 v036 的 VIDEO_COMMENT_PAGE_SIZE 是 30（e3e1914cb，10-13）。**已在 grokbot/source261-compile-fix 修复（4f71582bb），256 上没改** |

**这 28 项失败分属 16 个类，全都不在云端校验计划里**，所以云端 256 校验通过并不能说明这些测试没问题。

## 跟云端校验计划的对比
- 计划里有 139 个类（期望 1245 项），全部存在、全部执行，用例数逐类一致，全部通过。**没有“计划里有、仓库里却不存在”的类。**
- **实际执行了、但计划里没有的类：384 个**（523 个执行的类减去 139 个），分布在 55 个包里。最多的几个包：com.bilipai.desktop.ui 134、com.android.purebilibili.feature.* 42、com.bilipai.desktop.data 39、player 33、danmaku 26、settings 15。完整清单在 task5-uncovered-classes.txt，下面按包分组列出。
- 另有 3 个带标签的类（native-mux / js-worker / packaged-updater）不在计划里，也不在 `test` 任务里。

<details><summary>计划没覆盖的 384 个类（按包分组）</summary>

- `com.android.purebilibili.core.network.grpc`（1）：DynamicPersonalFeedApiTest
- `com.android.purebilibili.core.network.socket`（3）：DanmakuProtocolLimitsTest、LiveDanmakuClientTest、LiveDanmakuConnectionHealthPolicyTest
- `com.android.purebilibili.core.plugin.js`（4）：BiliPaiJsNativeExampleTest、BiliPaiJsPluginInstallStoreTest、BiliPaiJsPluginPolicyTest、BiliPaiJsRuntimeScriptPolicyTest
- `com.android.purebilibili.core.plugin.kotlinpkg`（2）：ExternalKotlinPluginInstallStoreTest、ExternalKotlinPluginPackageReaderTest
- `com.android.purebilibili.core.plugin.skin`（2）：UiSkinInstallStoreTest、UiSkinPackageReaderTest
- `com.android.purebilibili.core.theme`（3）：DesktopThemeRoleSpecContractTest、ThemeContrastPolicyTest、ThemeRoleOverridePolicyTest
- `com.android.purebilibili.core.ui`（6）：AdaptivePullToRefreshPolicyTest、AdaptiveScaffoldPolicyTest、AppChromeSizeTokensTest、AppTopChromePolicyTest、MatchedLiquidIndicatorGeometryTest、TopReadabilityChromePolicyTest
- `com.android.purebilibili.core.ui.animation`（2）：LiquidIndicatorMotionTest、LiquidIndicatorVelocityTest
- `com.android.purebilibili.core.ui.components`（2）：AppPrimitiveRendererPolicyTest、AppTextSearchHighlightTest
- `com.android.purebilibili.core.ui.renderer.material3`（1）：AppTonalPillTabPolicyTest
- `com.android.purebilibili.danmaku.parser`（2）：SpecialDanmakuIndexReaderTest、SpecialDanmakuWindowTest
- `com.android.purebilibili.danmaku.parser.bas`（5）：BasDurationTest、BasLexerTest、BasPathFrameTest、BasScriptParserTest、BasTimelineTest
- `com.android.purebilibili.data.repository`（6）：AicuRepositoryTest、AicuResponsePolicyTest、CommentReadRouteMemoTest、DesktopAicuCancellationTest、DesktopCommentReadRouteConsumptionTest、DesktopV029GradeSummaryPolicyTest
- `com.android.purebilibili.feature.aicu`（2）：AicuNavigationPolicyTest、AicuViewModelTest
- `com.android.purebilibili.feature.audio.library`（1）：DesktopV038ListenVideoLibraryLoaderTest
- `com.android.purebilibili.feature.download`（1）：OfflinePlaybackErrorPolicyTest
- `com.android.purebilibili.feature.dynamic`（2）：DynamicAccountCachePolicyTest、DynamicCommentLoadPolicyTest
- `com.android.purebilibili.feature.dynamic.components`（2）：DynamicCommentPreviewPolicyTest、DynamicLinkCardPolicyTest
- `com.android.purebilibili.feature.home`（1）：HomeLoadSequencePolicyTest
- `com.android.purebilibili.feature.home.components`（1）：FloatingBottomBarGeometryTest
- `com.android.purebilibili.feature.list`（1）：DesktopOriginalHistoryRetryTest
- `com.android.purebilibili.feature.live`（1）：LivePlaybackPolicyTest
- `com.android.purebilibili.feature.message`（1）：ChatTimelinePolicyTest
- `com.android.purebilibili.feature.message.notification`（1）：MessageNotificationPolicyTest
- `com.android.purebilibili.feature.plugin.js`（1）：BiliPaiJsPluginContentPolicyTest
- `com.android.purebilibili.feature.settings`（2）：AppearanceMd3ColorPickerPolicyTest、DesktopV029CacheClearUiPolicyTest
- `com.android.purebilibili.feature.video.ambient`（2）：AmbientFrameControllerTest、AmbientFramePolicyTest
- `com.android.purebilibili.feature.video.danmaku`（6）：BasDanmakuFilterPolicyTest、CommandDanmakuPolicyTest、DanmakuConfigPolicyTest、DanmakuConfigV029PolicyTest、DesktopV038DanmakuMergerPolicyTest、HotDanmakuPolicyTest
- `com.android.purebilibili.feature.video.playback.session`（1）：PlaybackLifecycleCoordinatorTest
- `com.android.purebilibili.feature.video.player`（1）：DesktopOriginalBackgroundPlaybackPolicyTest
- `com.android.purebilibili.feature.video.state`（1）：DesktopOriginalV029RecoveryPolicyTest
- `com.android.purebilibili.feature.video.subtitle`（1）：BiliSubtitlePolicyTest
- `com.android.purebilibili.feature.video.ui.components`（2）：DesktopV029CommentTimeTest、DesktopV029ReplyLinkPolicyTest
- `com.android.purebilibili.feature.video.ui.overlay`（7）：BottomControlBarLayoutPolicyTest、BottomControlBarPolicyTest、CommandDanmakuOverlayPolicyTest、CommandDanmakuOverlayStateTest、TopControlBarLayoutPolicyTest、TopControlBarPolicyTest、VideoProgressBarLayoutPolicyTest
- `com.android.purebilibili.feature.video.ui.section`（1）：PlayerPageBackdropPolicyTest
- `com.android.purebilibili.feature.video.viewmodel`（3）：DesktopVideoCommentCacheConsumptionTest、VideoCommentLoadPolicyTest、VideoInteractionStatusRetryTest
- `com.bilipai.desktop`（6）：DesktopApplicationRestartTest、DesktopCheckpointFailureTest、DesktopLibraryTest、DesktopLibraryTransactionTest、DesktopPlaybackControllerTest、DesktopPlaybackQueueOwnershipTest
- `com.bilipai.desktop.appearance`（8）：DesktopAppComponentsTest、DesktopAppPreferenceComponentsTest、DesktopAppearanceTest、DesktopComponentHostTest、DesktopLiquidLensSkiaTest、DesktopLiquidPointerMappingTest、DesktopWindowsDisplayScaleTest、DesktopWindowsLiquidGlassMigrationTest
- `com.bilipai.desktop.audio`（6）：DesktopMusicRootIntegrationTest、DesktopNativeMusicIntegrationTest、DesktopNativeMusicRouteTest、ListenAudioLifecycleTest、ListenAudioStoreTest、ListenWriterBarrierTest
- `com.bilipai.desktop.backup`（3）：DesktopBackupRestoreBarrierTest、DesktopBackupUpdateHoldTest、DesktopWebDavTest
- `com.bilipai.desktop.brand`（6）：BrandMotionRasterTest、BrandMotionSceneTest、BrandMotionSchemaTest、DesktopBrandComponentUiTest、DesktopMaidFrameClockPlayerTest、DesktopMaidMotionRuntimeTest
- `com.bilipai.desktop.cast`（3）：DesktopCastStreamHeaderTest、DesktopCastTest、DesktopOfflineLocalCastTest
- `com.bilipai.desktop.danmaku`（26）：AdvancedDanmakuTest、BasDanmakuParserTest、DanmakuHotBarTimelineTest、DanmakuLocalAppendPublicationTest、DanmakuLocalAppendTimelineTest、DanmakuPluginAdapterTest、DanmakuPresentationTimelineTest、DanmakuTest、DanmakuWindowLoaderTest、DesktopBasOverlayInputTest、DesktopBasOverlayPublicationTest、DesktopBasParseBudgetTest、DesktopBasPluginFilterTest、DesktopBasRenderPolicyTest、DesktopBasRendererTest、DesktopDanmakuAuthorBadgeTest、DesktopDanmakuDisplayAreaParityTest、DesktopDanmakuPresentationTest、DesktopDanmakuPresentationTimeTest、DesktopHotDanmakuProjectionTest、DesktopOriginalDanmakuFrameRequestsTest、DesktopSpecialOverlayPublicationTest、DesktopSpecialStreamingTransportTest、DesktopSpecialWindowIntegrationTest、DesktopV038DanmakuMergeBridgeTest、LiveDanmakuPluginTest
- `com.bilipai.desktop.data`（39）：BiliApiExceptionTest、DesktopArticleHistoryTest、DesktopBlockedUpRepositoryTest、DesktopBlockedUpStoreTest、DesktopCommunityMutationTest、DesktopCommunityRepositoryTest、DesktopDiscoveryRestoreLifecycleTest、DesktopDiscoveryTest、DesktopDynamicCacheTest、DesktopDynamicReactionListTest、DesktopFavoriteRepositoryResultSemanticsTest、DesktopLiveDanmakuOwnedSessionTest、DesktopLiveDanmakuSendTest、DesktopLiveModerationOwnedSessionTest、DesktopLiveModerationSourceBindingTest、DesktopLiveOwnedStreamTest、DesktopLiveProtocolTest、DesktopLiveSendTransportTest、DesktopLiveVoteOwnedSessionTest、DesktopLoginTest、DesktopMediaRepositoryTest、DesktopPersonalContentTest、DesktopPlaybackCacheExpiryTest、DesktopPlaybackCookieBoundaryTest、DesktopPlaybackHeartbeatTest、DesktopPlaybackSelectionTest、DesktopQrLoginCookiesTest、DesktopRecommendationTest、DesktopReplyMedalColorTest、DesktopRequestPolicyTest、DesktopSearchRestoreLifecycleTest、DesktopSessionStoreTest、DesktopSocialRepositoryTest、DesktopSpaceContributionsTest、DesktopSpaceOverviewTest、DesktopSpaceRelationActionTest、DesktopSpaceRepositoryTest、DesktopStoryTopicTest、DesktopV030LiveStreamRequestTest
- `com.bilipai.desktop.diagnostics`（1）：DesktopDiagnosticsTest
- `com.bilipai.desktop.download`（4）：DesktopDownloadBrandSuccessTest、DesktopDownloadManagerTest、DesktopDownloadRefreshQualityTest、DesktopDownloadSourceResolverQualityTest
- `com.bilipai.desktop.player`（33）：DesktopAdvancedPlaybackSettingsTest、DesktopLegacyPlaybackPreferenceChangesTest、DesktopNativeMuteIntentTest、DesktopNativePresentationTransferTest、DesktopNativeTerminalPresentationTest、DesktopNvidiaVideoAutoPolicyTest、DesktopPlaybackHeartbeatTest、DesktopPlaybackRecoveryTest、DesktopPlaybackWatchdogTest、DesktopPlayerPreferencesWriterTest、DesktopPremiumAudioControllerTest、DesktopPremiumAudioRecoveryTest、DesktopVideoEnhancementSessionTest、DesktopWindowsAudioOutputStoreTest、DesktopWindowsDxgiAdaptersTest、DesktopWindowsHdrDisplayTest、MpvBackdropFrameTest、MpvPlaybackIntentSeekAbaAdmissionTest、MpvStartupProbeTest、MpvVeyraPresentationTest、PlaybackSegmentsTest、PlaybackStreamHeadersTest、PlayerFailureTest、PlayerNvidiaActorWithdrawalTest、PlayerNvidiaTargetMetadataTest、PlayerNvidiaVideoTest、PlayerOwnershipTest、PlayerPreferencesTest、PlayerSeekTrackerTest、PlayerSelfTestCaptureTest、PlayerSelfTestFailureFrameDiagnosticTest、PlayerSoftwareScreenshotDiagnosticTest、PlayerVideoOutputTest
- `com.bilipai.desktop.plugins`（11）：DesktopGoogleCastDefaultTest、DesktopHistoryRecapStoreTest、DesktopPackageRepositoryTest、DesktopPluginDanmakuCancellationTest、DesktopPluginParityTest、DesktopPluginStoreSharingTest、DesktopPreferenceReplacementRetryWindowsTest、DesktopSkinRendererTest、DesktopTodayWatchParityTest、DesktopTodayWatchRefillTest、DesktopVideoEnhancementConfigurationTest
- `com.bilipai.desktop.plugins.js`（1）：DesktopJsRemoteMediaTest
- `com.bilipai.desktop.privacyfixture`（1）：IncognitoWriterTest
- `com.bilipai.desktop.settings`（15）：DesktopCacheClearComponentUiTest、DesktopCollectionPreferenceAdmissionTest、DesktopCommentFraudActualAdmissionTest、DesktopCommentFraudHistoryFilesTest、DesktopCommentFraudHistoryTest、DesktopDanmakuHotSettingsTest、DesktopHomeCardPreferencesTest、DesktopHomeFullCardTest、DesktopNavigationInteractionSettingsTest、DesktopOriginalAboutAvatarCoilTest、DesktopOriginalAppIconCoilTest、DesktopOriginalAppIconPreferencesTest、DesktopOriginalDonateResourceTest、DesktopSettingsNavigatorTest、DesktopSettingsSearchTest
- `com.bilipai.desktop.ui`（134）：DesktopAicuConsentStoreTest、DesktopBangumiAuthorizationRecaptureAssemblyTest、DesktopBangumiCourseDownloadOwnershipTest、DesktopBangumiDefaultQualityTest、DesktopBangumiPremiumAudioRecoveryAssemblyTest、DesktopBangumiVideoCodecRecoveryAssemblyTest、DesktopBrandSuccessBusinessTest、DesktopBrowseMemoryTest、DesktopChargedCommentProtocolTest、DesktopCommandPopupBytecodeTest、DesktopCompleteSpaceTest、DesktopCompleteSpaceUiTest、DesktopDecorativeWindowStylePolicyTest、DesktopDiscoveryStorageGuardTest、DesktopDynamicEditorUpdateHoldTest、DesktopDynamicEmoteCatalogTest、DesktopDynamicResourceNavigationTest、DesktopDynamicTimelineSettingsTest、DesktopExternalMediaTest、DesktopFavoriteCoverPrefetchRegressionTest、DesktopFeedbackCarrierPixelOracleTest、DesktopHistoryRecapComponentUiTest、DesktopHistoryRefreshTest、DesktopHomeCardProgressTest、DesktopHomeLoadFailureTest、DesktopHomeReturnSourceAdmissionTest、DesktopHotDanmakuNativeClockTest、DesktopInlineEmoteHighDpiTest、DesktopInlineEmoteScaleTest、DesktopLiveChatDraftTest、DesktopLiveChatFollowTest、DesktopLiveChatMessagesUiTest、DesktopLiveModerationMenuUiTest、DesktopLiveSourceRecoveryTest、DesktopLoginUpdateHoldTest、DesktopMessageNotificationEntriesTest、DesktopMessageUpdateInstallationHoldTest、DesktopMetadataRelatedRootNavigationTest、DesktopOfflinePlaybackErrorTest、DesktopOriginalCdnMediaReadinessTest、DesktopOriginalDanmakuSameSendProtocolAdmissionTest、DesktopOriginalDanmakuSameSendTest、DesktopOriginalDanmakuSliderPreviewTest、DesktopOriginalFavoriteSettingsReadbackTest、DesktopOriginalHomeSettingsOwnershipTest、DesktopOriginalInteractiveBranchAdmissionTest、DesktopOriginalListenCollectionPlaylistAdmissionTest、DesktopOriginalMessagePagesTest、DesktopOriginalNativeRecoveryAdmissionTest、DesktopOriginalOnboardingPreferencesTest、DesktopOriginalPlaybackFailureTest、DesktopOriginalPlaybackPreferenceOperationTest、DesktopOriginalPlaybackRecoveryTest、DesktopOriginalPlayerSettingsRetirementTest、DesktopOriginalPlayerViewportRecoveryTest、DesktopOriginalPortraitMetadataPreparationTest、DesktopOriginalSpacePagesTest、DesktopOriginalSponsorPresentationAssemblyTest、DesktopOriginalStartupAutoPlayReadbackTest、DesktopOriginalTextLyricsRegressionTest、DesktopOriginalVideoAuthorizationBootstrapTest、DesktopOriginalVideoAuthorizationRecaptureViewModelTest、DesktopOriginalVideoBgmTest、DesktopOriginalVideoCdnSelectionAssemblyTest、DesktopOriginalVideoChaptersTest、DesktopOriginalVideoCoverFileAdmissionTest、DesktopOriginalVideoEngagementStatusReadTest、DesktopOriginalVideoFallbackCauseProtocolTest、DesktopOriginalVideoFallbackCauseViewModelTest、DesktopOriginalVideoMetadataRelatedViewModelTest、DesktopOriginalVideoRequestCachesTest、DesktopPersonalRecapBindingTest、DesktopPersonalRecapSettingsTest、DesktopPluginJsonEditorUpdateHoldTest、DesktopRetainedMediaTest、DesktopSpaceBrowseTest、DesktopSpaceContributionBrowseTest、DesktopSpaceVideoBrowserTest、DesktopStoryInitialFailureTest、DesktopStoryInitialFailureUiTest、DesktopStoryNativeInputTest、DesktopStoryPlaybackHostTest、DesktopStoryTopicUiTest、DesktopSubtitleDialogTargetTest、DesktopTopicRoutingTest、DesktopUnifiedPlaybackAudioSelectionTest、DesktopV029ChatTimelineIntegrationTest、DesktopVideoBootstrapRetryIntentTest、DesktopVideoDynamicShareTest、DesktopVideoFavoriteSourceRetirementTest、DesktopVideoShareSourceSaveTest、DesktopWindowsBasActionsTest、DesktopWindowsCommandCardPolicyTest、DesktopWindowsCommandLinkBindingTest、DesktopWindowsCommandLinkUiTest、DesktopWindowsCommandVoteBindingTest、DesktopWindowsCommentComposerLayoutTest、DesktopWindowsCommentPreviewRequestTest、DesktopWindowsCommentSearchBindingTest、DesktopWindowsDialogEscapeBindingTest、DesktopWindowsEarlyVideoDetailTest、DesktopWindowsFullscreenChromeInteractionTest、DesktopWindowsFullscreenChromeStateTest、DesktopWindowsNativeDockGeometryContractTest、DesktopWindowsPlayerDialogGeometryTest、DesktopWindowsPopupMaterialUiTest、DesktopWindowsRelatedVideosRegressionTest、DesktopWindowsSharedLensUniformCacheTest、DesktopWindowsSponsorSkipCommandTest、DesktopWindowsSubtitlePreferenceStateTest、DesktopWindowsVideoAudioSelectionTest、DesktopWindowsVideoBgmSectionTest、DesktopWindowsVideoCollectionQueueTest、DesktopWindowsVideoCommentActionsTest、DesktopWindowsVideoDetailsPanelLayoutTest、DesktopWindowsVideoDownloadSelectionTest、DesktopWindowsVideoFeedbackLifetimeTest、DesktopWindowsVideoFeedbackOriginTest、DesktopWindowsVideoFeedbackPlacementTest、DesktopWindowsVideoFollowGroupRequestTest、DesktopWindowsVideoFollowGroupViewModelTest、DesktopWindowsVideoInteractionClassLoadingTest、DesktopWindowsVideoInteractionTest、DesktopWindowsVideoLoadErrorTest、DesktopWindowsVideoMetadataSectionTest、DesktopWindowsVideoScreenshotAdmissionTest、DesktopWindowsVideoSpeedOptionsTest、DesktopWindowsVideoStatisticsLeaseRegressionTest、WindowsActualMainRuntimeEvidenceTest、WindowsBrandFeedbackReplayTest、WindowsCommentComposerReplayTest、WindowsVideoActualResizeTargetTest、WindowsVideoDynamicShareReplayTest、WindowsVideoLocalReplaySearchTest
- `com.bilipai.desktop.update`（4）：DesktopUpdateMonitoringTest、DesktopUpdaterRegistryTransactionTest、DesktopUpdaterSafetyTest、StartupHealthTest
- `com.bytedance.danmaku.render.engine`（1）：RenderFrameCadenceTest
- `su.litvak.chromecast.api.v2`（1）：DesktopGoogleCastProtocolTest

</details>

## 证据分类
- 已在 Linux 编译通过：256 主代码和测试代码（干净的 build 目录）。
- Python 检查通过：本任务不涉及。
- JVM 测试通过：3984 项里 3951 项通过，28 项失败，5 项跳过；计划内的 1245 项全部通过。
- 只在 Linux 上成立的结论：(a) 类的 9 项在 Windows 上可能通过，没验证；JS worker 相关的类在 Windows 上要有真实资源。(b) 类各项的“在 Windows 上也会失败”是根据代码推断的，**没在 Windows 上跑过**。偶发那一项跟机器负载有关。
- 未验证：Windows 云端、三个带标签的冒烟测试、界面、GPU、音频、安装包。
- 本任务没修改任何代码或测试。
