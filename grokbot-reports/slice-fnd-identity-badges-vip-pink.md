# Roadmap 切片 #3 fnd-identity-badges-vip-pink

分支 grokbot/slices-batch1，代码提交 dd6ca97c1（基于 2f68e32d4 = 合入 integration-263@84c0542fa）。

## 已完成：userVipPink 的正式定义，并移除临时垫片
- 新归档 desktop/upstream-slices/v046-identity-badges/：v0.4.6 的 UserVipBadge.kt（d6bf8b16，blob ad4304d8，sha256 22a6971b…）+ LICENSE + manifest.json（sha256 c766fa05595473479850ae800d36fbd9bc11601528def40957688f9d6119cd75）。用 playbook 附录 A 工具生成，verify-archive 结果 ok。
- 新生成器 desktop/tools/v046_identity_badges.py（A 族写法，need/ValueError）：fixed()、validate_outputs()、restore()、advance()。
- extract-upstream-home-page.py 把它作为 UserVipBadge.kt 的**最后一层**。该文件在 upstream-sources.json 里是 policy-extract，由这个生产者负责，所以不需要 exclude。
  - 先校验前驱 sha（1b5aa17c…，钉在 manifest 的 beforeFinalOutputs 里），再整文件换成 v0.4.6。
  - 记录 originalToWindows 和 retainedToCurrent 两份逆向证明（v046-identity-badges-proof.json）。
- 现在 resolveUserVipPink、userVipPink、UserVipBadge、resolveVipBadgeColors、AccessibleVipBadgeColors 在整个编译集合里各只声明一次。
- 垫片已移除（按 T9 要求）：
  - 删除 desktop/src/.../ui/DesktopUserVipPink.kt 及其测试。
  - v046_comment_list.windows_vip_pink 不再替换调用，只保留“原始调用次数不变”的计数断言。
  - 生成的评论文件直接调用 `com.android.purebilibili.core.ui.components.userVipPink()`：ReplyComponents 2 次、SubReplyDetail 1 次、CommentSearchSheet 1 次。
- 登记（Root 例外 23bc370bd 第①-⑤条以内）：
  - inventory --apply：entries 736→737，distinct 722→723，buildInputRoots 135→136，archives 139→140。
  - catalog sha e681880b… → 487b448d332ba368298a277e9ae2b149a1659f88f6e4a6fef0f07e12f0bc3233，已写入 sync-upstream.py:36，以及 test_sync_upstream.py 的四处计数。
  - build.gradle.kts 只在 extractOriginalHomePage 任务里加了两行 inputs，**没有 exclude，没有其它构建逻辑**。
  - 没碰 upstream-sources.json、v025-canonical-sources.json、.github、已有归档，也没碰 windowsRevision。
- 已有测试的更新（playbook 6.3 类型，没有放宽任何断言）：
  - test_v046_comment_list_source.py：原来断言“用的是垫片”，改成断言“用的是原版 FQCN 调用”，次数仍是 2/1/1。另外新增断言：归档里 userVipPink 和 resolveUserVipPink 各声明一次，颜色值不变（D44E7D/FF6699）。
  - test_v043_home_recommendation_badges_source.py：home-page 的 generate() 现在先校验新归档，所以这个测试的临时夹具仓库里补拷了新归档。原断言“Output overlaps fixed badge source”一字未改。

## BLOCKED：调色板的另外 5 个文件
AppBadge.kt、UpBadgeNamePolicy.kt、UserUpBadge.kt、UserAvatarCornerMark.kt、OfficialVerifyBadgePolicy.kt 在 upstream-sources.json 里都是 **direct** 模式。按 playbook 4.4 第 2 条，整文件换成 v0.4.6 必须在 prepareUpstreamSources 里加 `exclude(canonicalOriginalIdentity(...))`。Root 例外第④条只允许 srcDir 和 inputs 接线，所以这部分没做，需要 Root 明确允许加这 5 行 exclude（或给出别的路线）。BangumiUiPolicy.kt 是 extracted 模式，属于 bangumi 生产者链，依赖番剧切片，本次也没做。

## 需要 Root 处理：符号闭包门的“过期已知缺口”
test_symbol_closure.py 有 3 项 CurrentIntegrationTree 失败（95 OK，2 项 Windows skip）：
- symbol-closure-expected-gaps.json 把 `com.android.purebilibili.core.ui.components.userVipPink`（scope carried，closedBy fnd-identity-badges-vip-pink）列为已知缺口。现在有了定义，这个缺口就成了 stale，门会按设计失败。
- 这正说明本切片关掉了这个缺口。退役它需要改 Root 的门数据（移到 retiredSeeds）和几处快照断言（第 1187、1232、1793 行附近）。现有 retiredSeeds 只有 member 类型的先例（reducedMotion），所以我没有改，请 Root 退役。

## 证据
- Linux 编译：extractOriginalHomePage 重新执行，compileKotlin 和 compileTestKotlin 都是 BUILD SUCCESSFUL，0 错误。生成的 UserVipBadge.kt 含 userVipPink（声明 1 次，使用 1 次）。
- Python（带垫片）：

  | 测试 | 结果 |
  |---|---|
  | test_v046_identity_badges_source | 7 OK |
  | test_v046_comment_list_source | 26 OK |
  | test_v046_comment_thread_source | 10 OK |
  | test_v043_home_recommendation_badges_source | 8 OK |
  | test_v043_home_native_cards_source | 10 OK |
  | test_v040_native_chrome_source | 9 OK |
  | test_v042_search_dependencies_source | 8 OK |
  | test_refresh_generation_reentry | 6 OK |
  | test_sync_upstream | 74 OK |
  | test_validate_source_cohort | 25 OK（1 skip） |
  | test_package_source_qualification | 14 OK |

- 闭包检查里会碰到、但与本切片无关的失败：
  - test_extract_upstream_home_load 1/5（DesktopOriginalHomeScreen.kt 的 v029 receipt，用改动前的生产者也得到同样两个哈希，是后加阶段没解链的旧问题）。
  - test_extract_upstream_brand_consumers（子进程丢了路径垫片，是 Linux 路径问题）。
- JVM：评论相关 23 个测试类 170 项全部通过（Linux）。
- 未验证：Windows 云端构建；VIP 徽章改成粉底白字后的真实显示效果。Root 需要重新冻结：新归档 3 个叶子、新 Python 测试文件 test_v046_identity_badges_source.py；没有新 JVM 测试类（删除了 DesktopUserVipPinkTest，原来 1 个类）。
