# T13 #4 fnd-sheet-motion-and-glyph（2026-10-19）

分支 grokbot/slices-batch1，提交 960e5a58a（父 8dfea431b）。只做 AppSheetComponents 一半。

## 做了什么

- AppSheetComponents.kt 是 selected 模式（dynamic-detail-container）。新归档 desktop/upstream-slices/v046-sheet-motion/（例外①）：v0.4.6 d6bf8b16 原样 AppSheetComponents.kt（blob 0a623997，sha 1693624e…）+ LICENSE + manifest（sha 42178c5a…），含前驱钉（v037 Windows 适配后正文的 sha）。v037-window-modal-blur 归档未动。verify-archive ok。
- 新生成器 desktop/tools/v046_sheet_motion.py（family A），在 extract-upstream-dynamic-detail-container.py 的 thread_container() 里追加为最后一层：断言前驱，逐块 exact-once 应用 v037→v0.4.6 增量（resolveAdaptiveBottomSheetMotionSpec 的 reducedMotion 参数、AppBottomSheetMotion.reducedMotion / dragSettleSpec 成员及由 LocalSystemReduceMotion/LocalComponentMotionEnabled 推出的取值、减弱动效的过渡、visible/onDismissStarted 受控关闭、contentColor 改为 AppContentColors.primaryText()）。所有 Windows 适配保留并有断言（DesktopCommentDialogNavigationHost、LocalWindowSizeClass、findRootCoordinates、neverEqualPolicy；无 LocalView/LocalConfiguration/android. 导入/铰链区域；decorFitsSystemWindows 仍删除）。没有需要语义决定而停下的块。记录 originalToWindows 与 retainedToCurrent 两个逆向证明，可还原 v0.4.6 原文与前驱。
- 清单 743/729/138/142 → 744/730/139/143，目录摘要 → 67369059d3bc1f369b751ab2f930760cd37b00b97a9eb2321f85c5e43abcf990（工具算出）。build.gradle.kts 只在 extractUpstreamDynamicDetailContainer 加两行 inputs。
- 现有测试：test_extract_upstream_window_modal_sheet.py 按 playbook 6.3 先用新证明剥掉本层，再与 adapt_sheet 结果逐字节比较（强度不变；头部 sha、exactCompleteInverse、sourceQualified 断言保留）。新测试 test_v046_sheet_motion_source.py 6 项。
- 上游 AdaptiveBottomSheetPolicyTest 未发射：该生产者没有测试 srcDir（不新增），且该测试读 Android 源文件路径并断言已被 Windows 适配移除的 findViewTreeNavigationEventDispatcherOwner。

## COMPAT_REDUCED_MOTION 能否退役（只报告，未删除）

- 现在 AppBottomSheetMotion 有真实成员 reducedMotion；Kotlin 中成员优先于同名扩展，所以 CommentContentReveal.kt / CommentCascadingContent.kt 里的 .reducedMotion 读到的是成员，DesktopOriginalCommentMotionCompat.kt 里的扩展变成死代码但仍可编译（编译已验证通过）。
- 删除会改变：v046_comment_list.py 不再发射 DesktopOriginalCommentMotionCompat.kt，v046-comment-avatar-shared-proof.json 的 emitted 列表变化；其它发射正文不变。
- 仍钉住它的地方：test_v046_comment_list_source.py（compat 文本）、test_symbol_closure.py（要求 COMPAT_REDUCED_MOTION 与该文件）、symbol-closure-expected-gaps.json retiredSeeds[0]（retiredBy 指向该 shim）。
- 结论：语义上已可退役，但需要 Root 同时更新上述测试与门禁数据（把 retiredBy 改成 v046_sheet_motion 的真实成员），我不改。

## BLOCKED

- 字形文字样式 appIconGlyphTextStyle 在 AppPrimitiveThemeDefaults.kt（direct 模式），整文件替换需要 exclude 行，超出例外④。AppNumberPicker/AppDurationPicker/AppFloatingToolbar/AppWindowActionMenu 未做（AppFloatingToolbar 仍是已知缺口，closedBy 本切片）。

## 证据分级

- Linux 编译：compileKotlin、compileTestKotlin 通过（0 错误），extractUpstreamDynamicDetailContainer 已重跑，输出含 12 处 reducedMotion。
- Python：新测试 6/6；test_extract_upstream_window_modal_sheet 4/4、test_extract_upstream_immutable_git_sources 4/4、test_v046_comment_thread 10/10、test_v040_native_chrome 9/9、test_v046_comment_list 26/26、test_sync_upstream 74/74、test_validate_source_cohort 25（1 个仅 Windows 跳过）、test_package_source_qualification 14/14。test_symbol_closure 98 项：unexpected 0；同样 4 个 CurrentIntegrationTree 失败（陈旧 6 个缺口 + 1 个已定义 latent，均来自 #3/#5/#6，待 Root 退役；#4 未新增陈旧项）。
- JVM：test --tests feature.video.ui.components.* 与 core.ui.*：22 类 117 项全过。
- 未验证：Windows 云端、Python 3.12 实跑、底部弹层的真实动效/关闭行为（无 UI 结论）。
