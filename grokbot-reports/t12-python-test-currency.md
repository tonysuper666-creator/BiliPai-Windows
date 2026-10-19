# T12 应用 T11 的测试补丁（按 Root 条件）+ 第 4 个旧失败

分支 grokbot/t12-python-test-currency，基础是 integration-263@6088437dc。只改了 desktop/tools/tests/ 下的 4 个测试文件，每个文件一个提交。没有改生成器或产品代码，没有删除或放宽任何原断言。

| 提交 | 测试 | 结果（Linux） | 需要路径垫片吗 |
|---|---|---|---|
| b68283b0f | test_extract_upstream_up_danmaku | 10/10 | 不需要 |
| 7d4e9fefe | test_extract_upstream_brand_success_mount | 3/3 | 需要 |
| 1143df4f8 | test_extract_upstream_comment_renderer | 5/5 | 不需要 |
| e9217976b | test_extract_upstream_v031_repost_coin | 4/4 | 需要 |

其它：test_sync_upstream 74 OK，test_validate_source_cohort 25 OK（1 skip），test_package_source_qualification 14 OK，三者带不带垫片都通过。brand 和 repost_coin 需要垫片，是因为它们调用的生成器总会加 Windows 的 `\\?\` 前缀；不带垫片时报 FileNotFoundError。

## A. up_danmaku
- 补丁：撤销元组最前面加 v029 comment-selection 阶段（38512320c 新加的）。
- 每个阶段仍然逐一校验 after sha、编辑字节和 before sha。

## B. brand_success_mount（按 Root 条件）
- **改动前保证的**：最终 fragment 的 sha 等于品牌 proof 的 after，并且含 `confirmBrandFollow(change.isFollowing)` 1 次。75522724c 之后这两条已经不可能成立。
- **保留下来的**：按 v037 interaction proof 解链（校验 after/before sha）。原有断言全部照跑在解链后的文本上：fullInverseExact、品牌 after sha、品牌回调 1 次、followStateEvents.confirm 存在、基线相等、去掉品牌 adapt 后 VM 和协议字节不变。
- **新增的，针对 Gradle 实际消费的最终产物**：
  - 最终 fragment 里 `confirmBrandFollow(change.isFollowing)` 是 0 次，且没有 `BrandSuccessEvents.followChanged`，证明旧回调确实被替换了。
  - DesktopOriginalVideoEngagementProtocol.kt 的 sha 等于 v037-detail-complete-output-readback.json 里对应那一行。
  - 选出的原版方法（经过已记录的 v042 confirmed-follow 投影）sha 等于协议 proof 的 beforeSha256LF。
  - 期望次数 = 原版方法里 `BrandSuccessEvents.followChanged(follow)` 的出现次数（这次是 1，并断言大于 0）。回放 indexedEdits 得到 afterSha256LF，映射后的方法里恰好有这么多个 `DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(follow)`，并且不含 BrandSuccessEvents。
  - 最终协议文件恰好包含这段方法文本 1 次、映射后的调用 1 次。
  - 所有生成的 *.kt 里都没有 `BrandSuccessEvents.followChanged`。
- 结论：品牌关注确认没有丢，只是位置从 fragment 回调换到了协议层，测试现在证明的是这一点。

## C. comment_renderer
- 先按外层优先解掉 v046 的 retainedToCurrent（校验 wholeInverseExact 和前后 sha），再跑原有的 v021 正文 sha、逆向、篡改拒绝断言。
- 过时的 `if (selectedTab == …COMMENTS)` 换成 Root 批准的两条断言（showComments，以及 COMMENTS 分支 + `Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) { comments() }`）。
- 后面两条断言保留，“评论内容放在有限高度的同级视口里”这个意图不变。

## D. v031_repost_coin（纯解链，所以直接放进本分支）
- 测试最后一次修改是 75522724c，在那里 4/4。bisect 找到的第一个坏提交是 38512320c（它的父提交 17705422e 通过）。原因是它在 PortraitVideoPager 的 v035 story 之后插入了 legacy_comment_selection_delta：在该提交上，story after 3ceff443… ≠ 67c41ea1…。之后 daff05d71 又加了 v042 owner-follow UI，最终文件变成 fdf7a9ee…。
- 哈希链从最终文件往回逐级吻合：
  1. fdf7a9ee = owner-follow UI proof 的 outputSHA256。
  2. 解掉它，得到 input = comment-selection 的 after。
  3. 再解掉 comment-selection，得到 before = story 的 after（3ceff443）。
  4. 原来的 story、native-preparation、repost 断言照旧成立。
- 同一个方法里原本被这个失败挡住、没执行到的两处，也用各自已记录的 proof 解链：
  - VM：b5434f08 → b4e70587，后者等于 v037-repost-contract 的 generatedSha256LF。
  - 协议：回放 ea3b67f1d 的 confirmed-follow 投影（previousSourceSHA256 → projectedSourceSHA256），再选出原版方法，sha 等于协议 proof 的 before（a8ed4e02）。
- 结论：测试过时，证明链完好，Gradle 消费的输出不受影响。

## 证据分类
- Linux 编译：不需要（只改了 Python 测试）。
- Python：上表全部通过（Linux，按需带垫片）。
- JVM：无。
- 未验证：Windows 上的 Python 运行（预期不需要垫片）。
