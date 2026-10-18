# Task 2 — Python 检查基线（同一分支 @ 610218f8b）

未修改任何仓库文件，未修复任何测试（按要求只标注）。

## 命令
- `python3 desktop/tools/run-tool-tests.py --stage policies`
- .github/workflows/windows-source-validation.yml 里的全部 85 条 `python -m unittest discover -s desktop/tools/tests -p <file> -v`（在本机用 python3 3.13.5 运行）
- 环境：`PYTHONDONTWRITEBYTECODE=1 PYTHONIOENCODING=utf-8 PYTHONPATH=desktop/tools`（工作流第一步就是这么设的）
- 诊断用的第二轮：在 PYTHONPATH 前面再加仓库外的 `\\?\` 前缀垫片（/workspace/g-logs/pyshim/sitecustomize.py），用来区分“只是 Linux 路径问题”还是“真失败”。

## 汇总
| 运行方式 | 通过的命令 | 实际跑到的用例数 | 失败的命令 |
|---|---|---|---|
| 按 CI 方式，不加垫片 | 46/86 | 759 | 40 |
| 加 `\\?\` 垫片 | 83/86 | 930 | 3 |

另有 15 个用例在两种方式下都是 Windows 专用 skip（veyra 3、mpv companion 5、mpv rtx installer 7），validate_source_cohort 有 1 个 skip。

**结论**：加垫片后仍失败的 3 条都是 Windows 专用；没看到跟平台无关的真实回归。不加垫片失败的 37 条，根因都是生成器无条件给路径加 Windows 长路径前缀 `\\?\`（不判断 os.name），在 Linux 上会读写到错误位置（还会在仓库根目录留下一个名为 `\\?\/` 的垃圾目录，我已经删了）。这是可移植性问题，不是逻辑回归。按要求没修。

| 加垫片后仍失败 | 原因 |
|---|---|
| test_fixed_java_toolchain.py | 需要真实 PowerShell 7（RuntimeError），Windows 专用 |
| test_original_root_ui_class_closure.py（1 失败/25） | 断言测试路径长度 >260（Windows MAX_PATH 场景），Linux 临时路径只有 247 字符 |
| test_v029_comment_composer.py（导入阶段错误） | 测试起的子进程不继承垫片，生成器的 `\\?\` 路径在 Linux 上找不到文件，Windows 路径问题 |

## 逐条结果
见下表（“用例数”一栏里 a/b 表示不加垫片/加垫片时跑到的用例数；不加垫片时有的模块在导入阶段就失败，所以是 0）。

| # | 命令 | 用例数 | 按 CI 方式（PYTHONPATH=desktop/tools，原生 python3） | 加仓库外 \\?\ 垫片后 | 失败原因分类 |
|---|---|---|---|---|---|
| 1 | run-tool-tests.py --stage policies | 193 | OK | OK |  |
| 2 | test_extract_upstream_command_attention.py | 3/3 | FAILED (errors=3) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 3 | test_extract_upstream_owner_follow.py | 2/2 | FAILED (errors=2) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 4 | test_package_source_qualification.py | 14 | OK | OK |  |
| 5 | test_validate_source_cohort.py | 25 | OK (skipped=1) | OK (skipped=1) |  |
| 6 | test_export_packaged_updater_harness.py | 22 | OK | OK |  |
| 7 | test_verify_updater_harness_export.py | 14 | OK | OK |  |
| 8 | test_veyra_verifier_child_environment.py | 3 | OK (skipped=3) | OK (skipped=3) | Windows 专用 skipUnless（nt+pwsh+powershell.exe），全部跳过 |
| 9 | test_mpv_companion_resource_staging.py | 5 | OK (skipped=5) | OK (skipped=5) | Windows 专用 skipUnless pwsh，全部跳过 |
| 10 | test_mpv_rtx_installer_protocol.py | 7 | OK (skipped=7) | OK (skipped=7) | Windows 专用 skipUnless pwsh，全部跳过 |
| 11 | test_native_cares_recipe.py | 6 | OK | OK |  |
| 12 | test_v038_listen_collection_source.py | 3/3 | FAILED (failures=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 13 | test_v038_audio_library_source.py | 5/5 | FAILED (errors=2) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 14 | test_v038_listen_viewmodel_source.py | 5/5 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 15 | test_v038_listen_screen_source.py | 3 | OK | OK |  |
| 16 | test_v038_danmaku_merge_source.py | 5 | OK | OK |  |
| 17 | test_v041_feed_images_source.py | 3 | OK | OK |  |
| 18 | test_v041_feed_store_source.py | 4 | OK | OK |  |
| 19 | test_extract_upstream_plugins.py | 17/17 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 20 | test_extract_upstream_js.py | 10 | OK | OK |  |
| 21 | test_v040_dynamic_pagination_source.py | 3 | OK | OK |  |
| 22 | test_dynamic_timeline_sources.py | 5 | OK | OK |  |
| 23 | test_dynamic_tabs_sources.py | 7 | OK | OK |  |
| 24 | test_v041_dynamic_author_search_source.py | 4 | OK | OK |  |
| 25 | test_v042_search_layout_source.py | 0/5 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 26 | test_v042_search_dependencies_source.py | 0/8 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 27 | test_v042_optical_dependencies_source.py | 0/7 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 28 | test_v042_network_proxy_source.py | 6 | OK | OK |  |
| 29 | test_extract_upstream_brand_callers.py | 6/6 | FAILED (failures=2) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 30 | test_v042_seed_palette_source.py | 3 | OK | OK |  |
| 31 | test_v043_theme_chrome_source.py | 8 | OK | OK |  |
| 32 | test_v046_danmaku_like_source.py | 7 | OK | OK |  |
| 33 | test_v046_favorite_editor_source.py | 0/9 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 34 | test_v046_comment_chrome_source.py | 0/9 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 35 | test_v044_favorite_detail_source.py | 0/7 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 36 | test_v044_favorite_subscription_source.py | 0/10 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 37 | test_v044_favorite_search_source.py | 0/7 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 38 | test_v044_audio_routes_source.py | 0/5 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 39 | test_v044_fans_source.py | 8/8 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 40 | test_v044_following_source.py | 10 | OK | OK |  |
| 41 | test_v044_list_foreground_source.py | 0/8 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 42 | test_v044_navigation_contracts_source.py | 8/8 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 43 | test_windows_audio_output_focus_sources.py | 9 | OK | OK |  |
| 44 | test_android_backup_format_sources.py | 3 | OK | OK |  |
| 45 | test_extract_upstream_brand_history.py | 6/6 | FAILED (failures=2) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 46 | test_v040_theme_roles_source.py | 4 | OK | OK |  |
| 47 | test_v040_liquid_controls_source.py | 6/6 | FAILED (failures=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 48 | test_v040_chrome_actions_source.py | 6 | OK | OK |  |
| 49 | test_v040_native_chrome_source.py | 0/9 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 50 | test_v040_player_cover_source.py | 4/4 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 51 | test_v041_player_cover_source.py | 0/12 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 52 | test_v040_player_tap_source.py | 4/4 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 53 | test_extract_upstream_offline_error_v029.py | 0/11 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 54 | test_v040_reply_medal_source.py | 3/3 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 55 | test_v029_comment_search.py | 0/7 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 56 | test_v029_reply_renderer.py | 3/3 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 57 | test_extract_upstream_dynamic_reply.py | 2 | OK | OK |  |
| 58 | test_v041_dynamic_comment_identity_source.py | 4 | OK | OK |  |
| 59 | test_windows_pgc_screen_mount.py | 0/11 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 60 | test_original_root_ui_class_closure.py | 25/25 | FAILED (failures=1) | FAILED (failures=1) | Windows 专用：测试断言临时路径长度 >260（Windows MAX_PATH 场景），Linux 上为 247 |
| 61 | test_original_root_ui_candidate_classpath.py | 20 | OK | OK |  |
| 62 | test_original_root_ui_runtime_binding.py | 22 | OK | OK |  |
| 63 | test_same_workspace_candidate.py | 6 | OK | OK |  |
| 64 | test_original_root_ui_runtime_image.py | 19 | OK | OK |  |
| 65 | test_original_root_ui_packaged_native.py | 14 | OK | OK |  |
| 66 | test_candidate_production_classpath.py | 11 | OK | OK |  |
| 67 | test_package_checkout_consistency.py | 2 | OK | OK |  |
| 68 | test_prepare_js_worker.py | 24 | OK | OK |  |
| 69 | test_export_original_root_ui_harness.py | 26 | OK | OK |  |
| 70 | test_verify_original_root_ui_export.py | 23 | OK | OK |  |
| 71 | test_windows_video_return_frozen_inputs.py | 6 | OK | OK |  |
| 72 | test_comment_selection_consumer_closure.py | 0/5 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 73 | test_v029_comment_composer.py | 0/0 | FAILED (errors=1) | FAILED (errors=1) | Windows 路径：子进程不继承垫片，生成器给路径加 \\?\ 前缀后在 Linux 上找不到文件 |
| 74 | test_v041_video_status_labels_source.py | 27 | OK | OK |  |
| 75 | test_v043_home_recommendation_badges_source.py | 0/8 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 76 | test_v043_home_native_cards_source.py | 0/10 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 77 | test_v043_search_up_author_source.py | 11 | OK | OK |  |
| 78 | test_v043_navigation_source.py | 14 | OK | OK |  |
| 79 | test_v043_message_link_host_source.py | 12 | OK | OK |  |
| 80 | test_v043_link_open_source.py | 12 | OK | OK |  |
| 81 | test_refresh_generation_reentry.py | 0/6 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 82 | test_v041_video_status_personal_consumers.py | 0/5 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 83 | test_profile_agreement_owner_source.py | 0/4 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 84 | test_v041_search_all_source.py | 0/8 | FAILED (errors=1) | OK | Windows 路径：生成器无条件加 \\?\ 长路径前缀，Linux 读写失败（加垫片后通过） |
| 85 | test_fixed_java_toolchain.py | 0/0 | FAILED (errors=1) | FAILED (errors=1) | Windows 专用：需要真实 PowerShell 7（RuntimeError: Actual PowerShell 7 is required） |
| 86 | test_rtx_hdr_color.py | 7 | OK | OK |  |

## 证据分类
- 已在 Linux 编译通过：不适用（见 task1）。
- Python 检查通过：policies 193 项，加垫片后 83/86 条命令；不加垫片的 CI 方式下是 46/86。
- JVM 测试通过：未运行。
- 未验证：Windows 上的实际结果（3.12 + PowerShell），这三条 Windows 专用失败在 Windows 上会不会通过。

## 环境事故（需告知）
13:51–13:52（伦敦时间）左右这台云电脑被重置了一次：/tmp、系统装的 JDK/GNU time/Adoptium 源、~/.gradle 缓存，以及 /workspace 下所有名叫 `build` 的目录都被清掉了，包括仓库里被跟踪的 `desktop/third-party/libmpv/build/**`、`desktop/verification/**/build/**` 这些文件。重置不是测试引起的。第一轮 Python 测试是在重置前跑完的，有效；重置后我对自己这份克隆执行了 `git checkout -- .` 恢复，再全部重跑，上表就是重跑结果。/workspace/BiliPai-Windows 和 /workspace/bigscreen-wt 这两份工作目录同样丢了这些被跟踪文件，我没动它们。
