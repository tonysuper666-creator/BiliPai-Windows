TON-1322：仅 227/228，Home 未读 gateway 与账号 sticky clean。

固定调查点：integration `b897c101b7cbc41d6b7bea91b88cc2faf282f797`，v078 PR #116
`5ff0c97f5f7d190ec38c1cf4761edd6f849b177a`；两者均保留旧的 Home REST 未读求和与未清理入口。
上游行为依据 `6f0696eb1389728b5a090125d32c1aae470f72f1` 的 HomeViewModel、MessageRepository、
MessageImRepository、MessageBadgeCleanState。未重审其他已接受项目。

实际消费链：`extractOriginalHomeViewModel` 读取固定 legacy 原文与 adaptations JSON →
`build/generated/home-viewmodel/.../DesktopOriginalHomeViewModel.kt` → 现有 transition patch →
`build/transition-patched/com/android/purebilibili/feature/home/DesktopOriginalHomeViewModel.kt` →
main sourceSet → `DesktopHomeRetainedEntry` → Root 的既有 `HomeHeader` 角标。
实际 Inbox 物理栈提交清理该 VM，返回触发其既有用户信息/未读刷新入口。

gateway 成功直接显示其总数；否则试 ImInterface，再失败才请求 IM total 与可选 feed REST。
IM 失败保留原值，feed 失败按 0；禁赞偏好沿用现有设置，Long 和各项非负饱和处理。
所有请求复用仓库客户端，经原有 epoch、Store receipt、调用者 Job 和发布门校验。
clean 状态仅在该 VM 内；清理增加 revision，使之前发出的响应无法建立清理后基准。
首次清理后响应、相等或下降保持 0，上涨恢复完整总数；账号重建及冷启动重新开始。

行为测试 `com.bilipai.desktop.ui.DesktopHomeMessageUnreadIntegrationTest` 创建真实 retained entry、
生成并经 transition 的 VM、Root 导航、SessionStore/Repository、owned request ports 与 gRPC/REST 解码。
仅 HTTP 服务返回合成数据，夹具暂停无关初始化任务，直接调用 VM 的私有 suspend 未读入口以保留
相同测试对旧代码的可编译性；不另造 reducer 或源码字符串断言。无 GUI、真实账号或远端网络。

复验（JDK 21，Gradle 必须套当时现行 Mac heavy FIFO）：

```sh
git diff --check
./gradlew -p desktop :test --tests 'com.bilipai.desktop.ui.DesktopHomeMessageUnreadIntegrationTest' --console=plain
python3 desktop/tools/tests/test_home_message_unread_sources.py --repo .legacy-root --receipt desktop/verification/home-message-unread/producer-inverse.json
```

首次 Gradle 调用会物化 `.legacy-root`。CLI 重放真实 producer，比较 Gradle 输出并精确逆转全部
96 项适配回固定原文；`producer-inverse.json` 保留原文、适配中间产物与最终输出的 SHA-256。
旧红对照仅还原 adaptations JSON 和 Root route assembly 到固定 integration；新增端口、HTTP 夹具和
同一行为测试保持不变，确保测到原 owner 与入口缺口，而不是制造另一套旧 reducer。

本轮结果：实现草稿，验收未达到。生产 Kotlin 编译与唯一类名检查通过；producer/inverse CLI
exit=0，96 项适配逆转精确且 Gradle 生成物相同；`git diff --check` exit=0。
两轮行为测试分别 3/12 与 2/12 通过，完整命令均 exit=1。第一轮全部 XML 为
460 passed / 56 failed / 0 skipped，第二轮为 459 / 57 / 0；其中 47 个失败来自
upstreamAppCoreSourceTests（46）及 sponsorPresentationTests（1）。未跑原实现对照，
不能断言这 47 项属于已有问题，也不能声称新增 fail/skip=0。

静态定位：gRPC 的 resolveBuvid 要求 visitor cookie；合成夹具未初始化它。首次修正误把
buvid3 放在 saveAccount 输入，而 SessionStore 的 ACCOUNT_COOKIE_NAMES 会过滤它，导致
第二轮仍未发出 gateway 请求。当前夹具已改为真实 saveSpiCookies；这次修正尚未编译或复验。
按岗位“同一原因失败两次就停手”收束，不继续重试，也不把测试零值误报为产品行为。

接续者先核当前夹具及所有失败，再套当时现行 heavy FIFO 执行上列同一行为测试；若修正后
通过，按上述方式只恢复两处旧 owner/入口做旧红，再恢复新实现做新绿。必须保存 finalizer
真实 exit，并比较所有 XML 的 task|class|name 身份；完成后再交监工安排异厂代码复核。
原始日志文件名中的 green-first/green-second 是尝试标签，均不是通过结果。
命令日志、实际 cwd/argv/exit、固定 SHA、每轮全部 test-results XML 随交付附件提供。
本轮没有旧红／新绿身份差集；两次失败尝试间的差集仅用于诊断。
定向结果不代表全门禁；GUI、Windows、扫码、真实服务器账号、广义 Inbox ACK 和其他跟版项未验。
不改 Root 构建、版本、基线或已有测试；验收由监工按容量交 Claude Sage。
