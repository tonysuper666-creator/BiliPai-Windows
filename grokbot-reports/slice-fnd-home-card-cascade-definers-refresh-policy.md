# T13 #6 第二部分：HomeRefreshUiPolicy.kt 整文件升到 v0.4.6（direct 模式 exclude 例外 ⑥）

依据：origin/desktop/grokbot-queue@9238072d9 的 STANDING-ORDERS 第 2 节例外 ⑥ 与 DECISIONS.md 2026-10-19“第二轮采用与 direct 模式整文件替换的 exclude 例外”（明确覆盖 #6 的 HomeRefreshUiPolicy.kt 和 VideoCardAdaptiveTintPolicy.kt）。本次只做 HomeRefreshUiPolicy.kt；VideoCardAdaptiveTintPolicy.kt 没做（见“未做”）。代码由 Grok Build 编写，我检查范围、编译、测试。

分支 grokbot/slices-batch1，代码提交 d530b0c13（父提交 a76efcd9b）。

## 改了什么
- 新归档 desktop/upstream-slices/v046-home-refresh-ui-policy/：v0.4.6 的 HomeRefreshUiPolicy.kt（git blob 994001b78…，与 `git show d6bf8b16:<路径>` 逐字节相同，已 cmp 核对）+ LICENSE + manifest.json（sha256 e6175b3d…213b）。verify-archive OK。
- 新生成器 desktop/tools/v046_home_refresh_ui_policy.py，作为 extract-upstream-home-page.py 的最后一步发射（validate 在 #6 第一部分的 validate 之后、徽章 overlap 检查之前）。输出 desktop/build/generated/home-page/com/android/purebilibili/feature/home/HomeRefreshUiPolicy.kt。
- desktop/build.gradle.kts：prepareUpstreamSources 里加 1 行 `exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/home/HomeRefreshUiPolicy.kt"))`（带一行注释，与邻行同写法）；extractOriginalHomePage 加 inputs.file/inputs.dir 两行。没有其它构建逻辑改动。
- 登记：compatibility-source-inputs.json 用 `inventory --apply`（新增 1 个输入行、3 个归档叶子）；COMPATIBILITY_CATALOG_SHA256 = 5cc878c7…b904，test_sync_upstream 计数 744→745 / 730→731 / 139→140 / 143→144，全部来自 `counts`，未手填。
- 新测试 desktop/tools/tests/test_v046_home_refresh_ui_policy_source.py（8 项）：归档/LICENSE/manifest 篡改与多余叶子拒绝；输出与归档和 git blob 逐字节相同；确定性与重跑；特殊目标（符号链接/目录/非普通文件）失败关闭；输出与归档重叠拒绝；接线与登记（exclude 行在构建文件和 prepareUpstreamSources 块里各恰好 1 次，门禁的 Gradle 解析也只看到 1 个 exclude；upstream-sources.json 里该路径仍是 "direct" 且未改；extract-discovery-platform.py 仍列为 "direct"）；**源码钉住**：在临时副本里用符号门禁的模型跑全部生产者（>100 个，0 失败），扫描 desktop/src 和 desktop/build，文件的每个顶层声明全仓恰好 1 个，且只在 generated/home-page 下，不在 generated/upstream 下——证明旧副本不再被编译、归档是唯一定义；resolveOldContentDividerContainerColor / OLD_CONTENT_DIVIDER_CONTAINER_ALPHA 不在其它任何文件声明。
- 已有测试只做了加严，没有放宽或删除：
  - test_v046_home_card_cascade_definers_source：原有断言全部保留；把生产者结尾的精确字符串断言扩成同样精确的新结尾（cascade emit → refresh emit → return written），并新增 refresh validate 的位置与次数断言。
  - test_v043_home_recommendation_badges_source：临时仓库里多复制新归档（否则 home-page 生成会先报“缺少输入”而到不了 overlap 检查）；overlap 断言本身不变。
- 未改：desktop/upstream-sources.json、v025-canonical-sources.json、symbol-closure-expected-gaps.json、.github、native、third-party、上游副本、已有归档、windowsRevision，没有新增调用方。

## 实际命令与结果（Linux）
- Gradle（bootstrap gradle.sh，-Xmx6g -Xss16m，单 worker）：`compileKotlin compileTestKotlin test -x prepareJsWorkerResources --continue --rerun --tests 'com.android.purebilibili.feature.home.*'` → BUILD SUCCESSFUL，0 个 `e:`；prepareUpstreamSources、extractOriginalHomePage 重跑；generated/upstream 下已没有 HomeRefreshUiPolicy.kt，generated/home-page 下有；HomeRefreshUiPolicyKt.class 用 javap 看到新函数 resolveOldContentDividerContainerColor(ColorScheme)。feature.home 包 2 个类 33 项全过（上游没有这个文件的单独测试）。`compileJava compileTestJava` → 成功（UP-TO-DATE）。
- Python（带垫片，独立 worktree，提交 d530b0c13）：新测试 8/8；test_v046_home_card_cascade_definers_source 8/8；test_v043_home_recommendation_badges_source 8/8；test_sync_upstream 74/74；test_validate_source_cohort 25（1 个仅 Windows 跳过）；test_package_source_qualification 14/14。Grok Build 在主 worktree 另外跑过：test_v046_identity_badges_source 7/7、test_v043_home_native_cards 10/10、test_v040_native_chrome 9/9、test_v042_search_dependencies 8/8、test_refresh_generation_reentry 6/6。已知旧失败不变：test_extract_upstream_home_load 1/5（v0.2.9 审计哈希）、test_extract_upstream_brand_consumers（子进程丢垫片，Linux 路径问题）。
- test_symbol_closure：98 项，4 个失败，**都是本分支原有的 4 个 CurrentIntegrationTree 失败**（门禁数据在本分支未退役）。门禁结果：unexpected findings 0；stale expected gaps 7（原 6 个 + 本切片新关闭的 `feature.home.resolveOldContentDividerContainerColor`）；latent 仍 OPEN 的 resolveWallpaperGlassAlpha 归 VideoCardAdaptiveTintPolicy。我没有改 symbol-closure-expected-gaps.json。

## 给 Root
- 门禁数据：摘入后请把 `[unresolved-import] com.android.purebilibili.feature.home.resolveOldContentDividerContainerColor`（fnd-home-card-cascade-definers 组）退役，和 bd7ff2324 里处理其它 6 个的方式一样。
- 摘入 desktop/integration-263-claude-main：我在临时 worktree 里试过 `git cherry-pick --no-commit d530b0c13` 到 bd7ff2324，只有 3 个登记文件冲突（compatibility-source-inputs.json、sync-upstream.py 的目录哈希、test_sync_upstream.py 的计数），其余 9 个文件干净。这 3 个应在你的头上用 playbook 附录 A 的 `inventory --apply` 和 `counts` 重算，不要手合。试完已 abort 并删掉临时 worktree。

## 未做
- VideoCardAdaptiveTintPolicy.kt（resolveWallpaperGlassAlpha）：它被 extract-upstream-home-full-card.py 的 tint 钉住（79e8…）引用，还有上游测试 VideoCardAdaptiveTintPolicyTest 在规范清单里；整文件替换要重锚那个钉住，并核对上游测试是否跟着变。这次时间窗口不够，没开始，留给下一轮（或 Root 决定）。
- #3 的 5 个调色板文件、#46 第一步、#4 字形一半：本轮没做。

## 证据分级
- Linux 编译通过：是（0 错误）。
- Python 通过：是（上面列出的全部；4 个门禁失败为本分支原有数据问题）。
- JVM 通过：feature.home 33 项；这个文件只是新增两个声明、其余逐字节不变，现有调用方行为不变。
- 未验证：Windows 云端；Python 3.12 实跑；界面上分隔条颜色（目前没有调用方使用新函数）。
