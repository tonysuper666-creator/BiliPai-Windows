# T13 #5 fnd-core-data-relocated-policies（2026-10-19）

分支 grokbot/slices-batch1，提交 753653526（父 7598d2cb4；已先合入 integration-263@6088437dc）。

## 做了什么（窄范围）

- 先按 firstStep 查清缺失集合（symbol-closure-expected-gaps.json + 生成物 grep）：data.model 里的 resolveVideoCardStatsTexts、resolveWatchLaterDisplayProgressState、resolveHistoryProgressLabel（proposed），latent resolveVideoDisplayProgressState（data.model），以及 core.network.BuvidManager。
- 新归档 desktop/upstream-slices/v046-core-data-policies/（例外①；T13.md 第 3 行明确 T9 例外适用于本任务）：v0.4.6 d6bf8b16 原样的 VideoProgressDisplayPolicy.kt（blob 2aacc3a0）、WatchLaterProgressPolicy.kt（b506583d）、VideoCardStatsTextPolicy.kt（503e8c90）和原版测试 VideoProgressDisplayPolicyTest.kt（027b94ea，4 个 @Test）+ LICENSE + manifest（sha 381c7a21…）。verify-archive ok，四个 blob 与上游对象逐一相等。
- 新生成器 desktop/tools/v046_core_data_policies.py（family A）：fixed()/validate_outputs()/emit()，三个主文件原样写到 v041 status-labels 生产者输出的 com/android/purebilibili/data/model/，测试写到它的 tests 输出。挂钩在 desktop/tools/extract-v041-video-status-labels.py（静态 import，v041 生成函数之后发射）。重复运行时覆盖普通文件（与该目录唯一所有者 write_all 一致），符号链接/目录/fifo 一律 fail closed。只用 Python 3.12 可用的 API（os.lstat + stat.S_ISREG），测试临时目录不写死 /tmp。
- 清单：inventory --apply（737/723/136/140 → 741/727/137/141），目录摘要 487b448d… → 79eed4f27ca6e536ea033c81070f73c67967a14fee3f6abbd60294f86037fb04（工具算出，sync-upstream.py 与 test_sync_upstream.py 的计数同步）。
- build.gradle.kts 只在 extractOriginalVideoStatusLabels 里加两行 inputs（tools/v046_core_data_policies.py、upstream-slices/v046-core-data-policies）。没有 exclude，没有新 srcDir（新声明，旧 feature.list 版本在另一个包，不冲突）。
- 现有测试只做“加严”修改：test_v041_video_status_labels_source.py 的输出黄金集合加入 4 个新文件及 sha，CLI 静态 import 允许集合显式加入 v046_core_data_policies（Grok 第一版用 __import__ 绕开该断言，已被我否决并改成显式静态 import）。
- 新测试 test_v046_core_data_policies_source.py（8 项）：归档篡改、输出逐字节等于归档与 git blob、确定性、重跑幂等、陈旧文件被替换、特殊目标 fail closed、重叠拒绝、接线/清单、11 个顶层名在所有生产者输出与 desktop/src 中各声明一次、测试 4 个 @Test、括号平衡。

## 没做 / 需要 Root

- BuvidManager 不搬：v0.4.6 版本是 Android 全局网络单例（NetworkModule、TokenManager、android.util.Base64），而 Windows 生成器有意把 BuvidManager.ensureBuvid3() 改写成所有者注入的访客调用（如 extract-upstream-dynamic-reply-protocol.py:248）。这是设计取舍，留给 Root；该缺口仍按预期存在。
- FeedContentTextRoles/FeedContentTokens、SubtitleContentRepository、DynamicFeedPageDataSource、CommentPaginationPolicy 未搬：当前没有被携带归档需要的缺失符号（门禁未报）。
- 符号门禁数据（禁止我改 symbol-closure-expected-gaps.json）：本切片使 3 个预期缺口变陈旧（data.model.resolveVideoCardStatsTexts、resolveWatchLaterDisplayProgressState、resolveHistoryProgressLabel），并使 latent 种子 resolveVideoDisplayProgressState(data.model) 变为已定义。test_symbol_closure.py 的 CurrentIntegrationTree 因此从 3 个失败（userVipPink 陈旧，#3 造成）变为 4 个（新增 test_the_compat_extension_retires_the_twelfth_seed_in_the_real_tree，它断言没有 latent 种子已定义）。需要 Root 退役这些条目；gate 运行：unexpected 0，stale 4，latent defined 1。
- 尚无消费者使用这三个新策略（携带归档里的引用现在可解析；接入消费者属于后续切片如 card-geometry-lists-and-grid）。

## 证据分级

- Linux 编译：compileKotlin、compileTestKotlin 通过（0 错误，增量构建，extractOriginalVideoStatusLabels 在已有输出目录上重跑成功）。
- Python：test_v046_core_data_policies_source 8/8，test_v041_video_status_labels_source 27/27，test_v043_home_native_cards 10/10，test_v042_optical_dependencies 7/7，test_v043_search_up_author 11/11，test_sync_upstream 74/74，test_validate_source_cohort 25（1 个仅 Windows 跳过），test_package_source_qualification 14/14；test_symbol_closure 98 项中 4 个 CurrentIntegrationTree 失败（见上，门禁数据待 Root）。
- JVM：test --tests 'com.android.purebilibili.data.model.*'：7 类 34 项全过，含新 VideoProgressDisplayPolicyTest 4/4。
- 未验证：Windows 云端构建、Python 3.12 实跑（只做了 API 静态检查）、任何 UI/真机行为。
