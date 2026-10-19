# Roadmap 切片 #46 mfx-small-protocol-fixes：第一步 BLOCKED（direct 模式文件需要 exclude）

基础：grokbot/slices-batch1 @ 30f492bf7。只做了只读分析，没有改代码。

## 第一步：私信空历史的 UINT64_MAX 游标
- 上游 v0.4.6（d6bf8b16）的修复：
  - 新文件 core-data/.../model/response/MessageHistoryCursorSerializer.kt：把 "18446744073709551615" 映射为 0，其它值转 Long 后 coerceAtLeast(0)。
  - MessageResponse.kt 第 196 行在 `min_seqno` 上加了一行 `@Serializable(with = MessageHistoryCursorSerializer::class)`。除此之外两版的 MessageResponse.kt 完全相同。
- Windows 的情况：MessageResponse.kt 在 upstream-sources.json 里是 **direct** 模式（source-parity），由 prepareUpstreamSources 原样拷贝。Windows 的私信记录走 DesktopCommunityRepository.messageHistory → communityMessageHistory（DesktopCommunityModels.kt:104），直接用这个生成的类型解析 JSON。所以“空历史返回 UINT64_MAX，Long 解析失败”这个缺陷在 Windows 上很可能真实存在（Linux 上没有用真实账号验证，也不能验证）。
- 为什么卡住：要把 direct 模式的 MessageResponse.kt 换成 v0.4.6，必须在 prepareUpstreamSources 里加一行 `exclude(canonicalOriginalIdentity(".../MessageResponse.kt"))`，再由新归档提供整文件。Root 例外第④条只允许 srcDir 和 inputs，不允许 exclude，所以停在这里。
- 需要 Root 选一个：
  1. **允许加 exclude 行**（推荐）：新归档 v046-message-history-cursor（MessageResponse.kt + MessageHistoryCursorSerializer.kt + LICENSE + manifest），整文件携带，加一行 exclude 和 inputs。改动很小，而且和上游完全一致。
  2. 桌面自有的变通：在 desktop/src 的私信历史读取处，先把 JSON 里的 UINT64_MAX 规范化再解析。这偏离上游实现，不推荐，没有 Root 同意我不做。

## 其余四个微修复（没做，原因）
- SponsorBlock 投票配置（Origin / x-ext-version 头依赖 BuildConfig.VERSION_NAME，需要 Windows 适配，服务端是否接受也未知）
- 专栏公开笔记回退
- 日志脱敏（Android 路径规则不覆盖 Windows 盘符，需要另写 Windows 自有扩展）
- 相册二维码解码

这四个各自分散在不同的生产者里。按路线图，第一步应该先做；第一步卡住后，我优先把 #2 和本轮的 VERIFY/T12 做完，这四个留到下一轮或等 Root 决定第一步之后再做。

## 同类阻塞汇总（Root 一次决定即可）
#3 的 5 个徽章文件（AppBadge、UpBadgeNamePolicy、UserUpBadge、UserAvatarCornerMark、OfficialVerifyBadgePolicy）和这里的 MessageResponse.kt，都是“direct 模式文件需要一行 canonical exclude”。如果 Root 把例外扩展为“该切片整文件替换的 direct 文件，允许在 prepareUpstreamSources 加对应的一行 exclude”，这两处都可以马上做完。
