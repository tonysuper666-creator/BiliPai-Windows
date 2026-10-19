# Roadmap 切片 #25 live-data-correctness：第 1 步（只分析，无代码改动）

基础：grokbot/slices-batch1 @ 9a1b99e74（已合入 integration-263@3c4a93457）。

## 路线图第 1 步
“给 LiveContributionRankType 加 apiValue，并在 tools/v030_live_stream.py 的 LiveRepository 里使用”。依据是 v030-live-stream/LiveRepository.kt:1004 仍发送 `type.name.lowercase()`。上游 v0.4.6（d6bf8b16）的 LiveRepository.kt:338-346 加了 `apiValue`（online_rank、daily_rank、weekly_rank、monthly_rank），:1096 改为发送 `"type" to type.apiValue`。

## 核查结果：Windows 上没有这个请求
- 归档里的 `getLiveContributionRank(...)`（v030-live-stream 和 v034-live-vote 的 LiveRepository.kt:990-1004）**不在 Windows 编译集合中**。生产者 `extract-upstream-media.py:403-418` 只取 `object LiveRepository {` 之前的模型/解析前缀，生成 `DesktopLivePolicies.kt`；按该处注释，仓库方法改由 Windows 薄适配器调用。
- 在整个编译集合（desktop/build/generated 全部 + desktop/src）里，`LiveContributionRankType` **只有声明，没有任何使用**（`DesktopLivePolicies.kt:310`）。Retrofit 声明 `DesktopUpstreamApi.getLiveContributionRank`（generated/api/…:659）也**没有调用方**。desktop/src 里没有贡献榜 UI 或请求。
- 结论：路线图写的“真实的请求参数缺陷”在 Windows 上**不会触发**，因为 Windows 根本不发这个请求。

## 决定
按 T9“发现路线图条目与实际不符就写进报告，跳到下一个”的规则，**本步不改代码**：
- 只给枚举加 `apiValue` 而没有调用方，等于新增死代码，还会改动由规范 v0.2.5 前缀生成的输出（有哈希证明链），收益为零。
- 正确的时机是将来把直播贡献榜（UI + 请求）搬到 Windows 时：届时按 v0.4.6 整段携带枚举和请求，直接用 `type.apiValue`。建议在路线图里把这一条挂到“直播贡献榜”切片下，并去掉“已验证缺陷”的说法（Windows 上不适用）。
- #25 的其余步骤（SuperChat 过期改用 endTime、LiveDanmakuRenderer、颜色、收藏标签、MPV 恢复映射）本次没做，需要真实直播间验证，留到后面。

## 证据分类
只读核查（rg 覆盖生成目录 + desktop/src，读生产者源码，`git show d6bf8b16:<path>` 读上游）。没有编译，没有运行测试，也不需要。
