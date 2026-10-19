# Roadmap 切片 #2 cmp-third-party-notices

分支 grokbot/slices-batch1，代码提交 ca4cc799c。合规方面只写事实，不是法律意见；GPL-3.0 对 Windows 分发意味着什么，需要人来决定。

## 只读调查（事实）
- Windows 构建实际携带的第三方来源资源：
  - build.gradle.kts:4185 把 upstream-slices/v037-shared-tabs-progress-closure/piliplus_custom_icons.ttf 拷进 fonts/（PiliPlusDanmakuSendIcon 使用）。
  - build.gradle.kts:4186 把 upstream-slices/v037-player-runtime-resources/ic_pili_tv_play.png 拷进 icons/（DesktopWindowsVideoEntryCover.kt:253 使用）。
  - 编译的 liquid-lens 代码：Lens.kt、LiquidLens.kt、AppLiquidValueControls.kt、AppElasticPress.kt 的文件头写明改编自 Kyant0/AndroidLiquidGlass（Apache 2.0）。Vibrancy.kt、InnerShadow.kt、CombinedBackdrop.kt 和这些文件同目录。LiquidGlassShader.kt 的文件头自称是 BiliPai 自有的着色器。
- 改动前**没有**随包分发 PiliPlus 或 AndroidLiquidGlass 的许可声明：
  - desktop/src/main/resources/licenses/ 下只有 appearance、dynamic-media、google-cast-v2、home-card-platform、jsoup、pinyin4j、rich-content、tinypinyin；desktop/resources/common/licenses 只有 coil。
  - 根目录的 THIRD_PARTY_NOTICES.md 只提到 NagramX 和 HyperIsland。
  - 本仓库钉住的上游副本里也没有上游后来加的 artwork/third-party、docs/third-party、design-system/src/main/assets/licenses。
- 上游 v0.4.6 已经有这些声明文件，路线图里列出的就是它们。readability4j（随 RSS 切片）和 Nagram 说明按路线图标注“later”，本次没做。

## 改动
- 新目录 desktop/src/main/resources/licenses/upstream-carried-assets/。普通资源目录，processResources 默认就会打进 classpath，所以不改构建，也不碰任何白名单。已用 Gradle processResources 确认它出现在 build/resources/main/licenses/upstream-carried-assets/。目录内容：
  - 从 d6bf8b16 用 git cat-file 字节级拷贝的 5 个文件（均无 CR）：

    | 资源 | 内容 |
    |---|---|
    | AndroidLiquidGlass.LICENSE | Apache-2.0，Copyright 2025 Kyant；原文末尾没有换行，保持原样 |
    | piliplus-LICENSE.txt | GPL-3.0 全文 |
    | piliplus-artwork-LICENSE | 与上一项同一 blob |
    | piliplus-tv-play.md | ic_pili_tv_play.png 来自 PiliPlus assets/images/play.png，修订号 1c19925b，未修改 |
    | piliplus-artwork-README.md | piliplus_custom_icons.ttf 来自 PiliPlus assets/fonts/custom_icon.ttf，未修改，GPL v3 |

  - provenance.json：每个文件的上游路径、字节数、sha256、gitBlob，以及它覆盖哪些 Windows 携带文件。
  - NOTICE.txt：用事实陈述每份声明对应哪些携带的资源或源码，以及它们在 Windows 的打包路径。
- 新测试 desktop/tools/tests/test_third_party_notices_source.py（5 项）：
  - 字节与上游 blob 一致。
  - sha、字节数、blob 与 provenance 一致。
  - 目录清单完全匹配。
  - NOTICE 提到每个被覆盖的资源。
  - 被覆盖的两个资源文件以及 build.gradle.kts 里的两条 from(...) 拷贝行确实存在。
  - 没有 CR。
- 没改 THIRD_PARTY_NOTICES.md、build.gradle.kts、任何白名单或已有文件。

## 留给 Root 决定
- 这个新目录目前只进 classpath（和 dynamic-media、jsoup、rich-content 等一样）。如果还要镜像到 desktop/resources/common/notices/（像 google-cast-v2、appearance、tinypinyin 那样，由 Sync 任务完成），需要新增构建任务，不在我的权限内。
- 是否在根目录 THIRD_PARTY_NOTICES.md 和应用内的“开源许可”页里加条目，由 Root 决定。
- GPL-3.0 资源对 Windows 分发的影响，需要人来判断。

## 证据
- Linux 构建：Gradle processResources 成功，新目录进了资源输出（只增加资源，不影响 Kotlin 编译）。
- Python（带垫片）：
  - test_third_party_notices_source 5 OK
  - test_verify_original_root_ui_export 23 OK
  - test_original_root_ui_packaged_native 14 OK
  - test_validate_source_cohort 25 OK（1 skip）
  - test_package_source_qualification 14 OK
- JVM：无。
- 未验证：Windows 安装包里是否包含这个目录（安装包环节由 Root 和 CI 负责）。Root 需要重新冻结：新 Python 测试文件，以及新增资源的路径。
