TON-1348：互动弹幕关注卡片关闭

基线：`dcc8f5f6f447ab6380c9d7edb3a5ed96ed327768`，落点 `desktop/integration-263-claude-main`。开工已核当前消费者及 30 个开放 PR 的文件；本卡四个代码文件没有重叠。TON-1220 的关注契约测试及 native popup 不在本卡写域。

真实链路为 `extract-stable-video-votes.py` → `v046_danmaku_hot_command.advance_command_overlay` → 生成的 `DesktopOriginalCommandDanmakuOverlay.kt` → `DesktopWindowsSectionCommandOverlay`。v046 的完整原文、源 manifest 和 pin 保持不变；增加可逆的 attention 外侧关闭补丁，延用现有 `OutsideCommandDanmakuCloseButton`。卡片宽度计入既有 overhang，移除旧内置关闭，确保类型 0/1/2 均只有一个关闭入口。输入节点按 `state, item.id` 定位，切视频时取消旧手势。

`DesktopCommandAttentionDismissUiTest` 使用生产 Section 包装层、真实视频来源适配器、真实 `rememberCommandDanmakuOverlayState` 及生产生成的卡片。CPU `ImageComposeScene` 中实际发送指针 press/release，语义仅用于定位，不直接调用 dismiss 或语义 OnClick。测试覆盖类型 0/1/2、已关注/未关注、重复关闭、按下期间切视频且复用 ID、卡片过期、关注/三连及投票回调。网络回调一旦调用就抛错。

复跑命令（cwd 为个人仓库 checkout）：

```sh
python3 desktop/tools/extract-stable-video-votes.py --help
python3 desktop/tools/extract-stable-video-votes.py .legacy-root "$PRIVATE_OUTPUT"
python3 -m unittest desktop.tools.tests.test_ton1348_attention_close -v
./gradlew -p desktop :test --tests 'com.bilipai.desktop.ui.DesktopCommandAttentionDismissUiTest' --console=plain
git diff --check dcc8f5f6f447ab6380c9d7edb3a5ed96ed327768 HEAD
```

`.legacy-root` 由现有 Gradle settings 准备，固定读点为 `d9595dc75cfe8d5c7128cb76352a607953b8bab6`。`PRIVATE_OUTPUT` 须指向自己可写且不覆盖原文的目录。生产 Gradle 任务采用相同读点及 producer，不要把合并上游后的主树冒充旧 pin 输入。

旧红使用基线版 v046 模块和本次同一份 Kotlin 测试；候选仅切换 v046 实现。测试文件 SHA-256：`59cc0bea2f87e5e70203d34bbd4f3fdf082f27fb53311b66ac0f34bb775108ae`。

验证结果：新增 Python 4/4，exit 0；producer `--help` 与正式生成均 exit 0。相同 Kotlin 测试在旧版 2 通过 / 5 失败，在候选 7/7 通过。旧版五项失败均为生产卡片找不到可点击的“关闭提示”。

| 全部任务 XML | 旧版 | 候选 |
| --- | ---: | ---: |
| 总用例 | 511 | 511 |
| 通过 | 459 | 464 |
| 失败 | 52 | 47 |
| 跳过 | 0 | 0 |
| 整条 Gradle exit | 1 | 1 |

按 `task|classname|name` 比较：新增失败 0、新增 skip 0、缺失身份 0、修复 5。保留的失败为 `upstreamAppCoreSourceTests` 的 46 条与 `sponsorPresentationTests` 的 1 条 `DesktopOriginalSponsorPresentationAssemblyTest.initializationError`，身份前后一致。后者抛出 `DesktopJsWorkerResources.kt:22` 的 IllegalArgumentException。`upstreamDesignSourceTests` 59 条、`applicationImageLoaderOwnerTests` 3 条均通过。目标 `:test` 已绿，整条命令仍红，不能宣称全局门禁通过。

源 manifest SHA-256：`097217edeff6588ba4f0c47c149912d8869754763714a86f2c9711c5324467cb`。
完整原文 SHA-256：`925f9d1f27053856c6c1e9a3f7395053b5b4ec797d24a8ebc8cff9ad9e15d8bf`。
旧输出 SHA-256：`1b663873bb0f707bc258246cce75512166c42346c23a9d17b1ee89fe51cc4025`。
候选输出 SHA-256：`966a621239c2668102d7ce1e23fa06352610602d5c9e4f5c464d94d898fd10e6`。

两次生成均通过 receipt 输出哈希与完整原文逆向校验；Python 另核前一层的逆向还原、输出篡改拒绝及 Vote/Follow/Triple 函数原文保持。候选私有 CLI 输出与 Gradle 真正消费的生成文件逐字节一致，SHA 如上。附件证据包中的 `generated-overlay.diff` 展示真实生成差异（保留原始 unified diff 空白上下文，不作为源码提交）。

完整证据包由本卡交付评论附带：两次完整日志、argv/cwd/exit、全任务 XML、按身份比较 JSON、输入输出凭证与哈希、生成差异及比较脚本。基线 v046 文件已证明逐字节等于落点基线；测试文件在前后两次运行中的 SHA 相同。

已知边界：独立运行旧套件 `python3 -m unittest desktop.tools.tests.test_v046_danmaku_hot_command_source -v` 得到 8 项执行、2 个 setUpClass 错误：其调用 `generate(REPO, ...)`，而当前合并主树不再符合旧 overlay pin。本卡不修改该套件或 pin。新验证执行实际 producer 的正式 legacy 读点，并核验其凭证与输出。

本卡未启动 GUI、登录、网络写操作、native 播放或 Windows 真机测试；不代表已完成 native popup、Windows DPI/命中区域或全局门禁验收。异厂 QA-C 由监工按容量续派，本 PR 等待独立验收。
