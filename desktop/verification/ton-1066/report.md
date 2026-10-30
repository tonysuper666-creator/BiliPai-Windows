结果：部分达到。新增 12 项验证码回调行为测试全部通过；11 个登录待迁文件全部保留，尚未完成上游直编迁移。

基线：`4768375f0167599666ed19c710b80df4acf52101`。候选为本任务独立检出内冻结后的源码，尚未做最终完整迁移的干净 PR head 验收。

本次改动把配置加载、浏览器启动和回调等待纳入同一个五分钟期限；超时会取消 HTTP 调用并关闭监听端口。浏览器加载失败、SDK 异常、取消和回传失败都有明确提示。回调只接受当前端口的单一 Host/Origin，拒绝重复头、非法数据和已结束会话的重放。成功响应关闭后才唤醒调用者，避免关闭服务器抢先中断浏览器响应。

## Mac 验证

两侧 `:compileKotlin :compileTestKotlin` 退出码均为 0。四个 JVM 任务均实际执行（`--rerun`），全量命令退出码均为 1，下面保留全部既有失败。表格为「测试 / 失败 / 跳过」；errors 均为 0。

| JVM 任务 | 基线 | 候选 | 新增 / 未复现失败 |
|---|---:|---:|---:|
| `test` | 6596 / 52 / 13 | 6608 / 52 / 13 | 0 / 0 |
| `upstreamAppCoreSourceTests` | 440 / 95 / 0 | 440 / 95 / 0 | 0 / 0 |
| `upstreamDesignSourceTests` | 59 / 11 / 0 | 59 / 11 / 0 | 0 / 0 |
| `upstream-core:test` | 218 / 2 / 0 | 218 / 2 / 0 | 0 / 0 |

JVM 总数 7313 → 7325，160 个失败和 13 个跳过全部对应同一用例。没有新增失败需要定性。`verifyUniqueClassNames` 两侧均输出 no duplicates，即 duplicated=0。

| Python 套件 | 基线 | 候选 |
|---|---|---|
| `desktop/tools/ux` | 1161; FAILED (failures=11, errors=5) | 1161; FAILED (failures=11, errors=5) |
| `desktop/tools/tests` | 1386; FAILED (failures=93, errors=201, skipped=24) | 1386; FAILED (failures=93, errors=201, skipped=24) |

两套 Python 套件失败/错误数量不增；用例明细见附件 `python-comparison.json`。

`node desktop/verification/ton-1066/check-captcha-page.cjs`：10 个脚本行为场景通过。它执行生产 HTML 的内联脚本，使用受控 DOM/SDK/fetch；不能代替真实浏览器和极验验证。

最终全量运行中的 `DesktopCaptchaBridgeTest` 为 12 / 0 / 0（测试/失败/跳过）。现有 `DesktopLoginTest` 与 `DesktopQrLoginCookiesTest` 合计 15 项通过，包括 `candidateSessionIsTemporaryAndDoesNotOverwritePersistedCredentials`、`staleResponsesAndVisitorBootstrapCannotCrossAnAccountSwitch`、`twoSavedAccountsHaveIndependentTokensAndVisitorCookiesAfterRestart`。这些旧测试不代表新 WebCookieSync 或完整注销清理已经实现。

## 命令与环境

完整命令、退出码、耗时在 issue 附件的 `stages.json`，原始输出在 baseline/candidate 日志中。两侧均沿用 TON-987 verifiers.zip 的 Mac 验证适配：POSIX 长路径 shim、已审核 native hash 与零值 JS-worker 编译输入，排除四个 Windows 产物任务；Mac 凭据使用已有 test-only 保护。未改产品源码、测试断言或门禁来适配这些环境条件，也不把它们算作 Windows 产物证明。

```sh
./gradlew -p desktop :compileKotlin :compileTestKotlin
./gradlew -p desktop :test --rerun :upstreamAppCoreSourceTests --rerun :upstreamDesignSourceTests --rerun :upstream-core:test --rerun :verifyUniqueClassNames --rerun
python3 -X utf8 -B -m unittest discover -v -s desktop/tools/ux -p "test_*.py"
python3 -X utf8 -B -m unittest discover -v -s desktop/tools/tests -p "test_*.py"
```

Gradle 上述命令实际追加同一 init script、8 GB heap / 16 MB stack，以及 `-x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker -x prepareJsWorkerResources`。Python 3.15.2、JDK 21.0.13、Gradle 9.5.0；Python 和测试 JVM 的临时根都在两个源码检出之外。预运行曾误用仓库内临时根，已中止并废弃那次 Python 统计，保留为 pre-external-tmp 日志。

## 仍需完成

- 登录目录 11 个文件、上游 LoginViewModel/CaptchaManager/LoginScreen 与 WebCookieSync 的直编迁移；扫码仍使用现有 DesktopLoginRepository。
- 新 Cookie 同步、注销与过期行为的完整验证。现有保存账号行为未更改，不能声称退出会删除所有已保存凭据。
- Windows VM 的一次构建、登录对话框与二维码过期刷新截图，以及真实极验回调录屏或大声失败证据。当前没有启动 GUI 或连接 Windows 主机。
- 完整迁移后的干净 PR head 复跑、桌面保活清单验收、异源 reviewer 与 cross_review。

`AdvancedLoginDialog`、外壳、设置、上游原文件及 AppUpdateChecker 均未修改；本轮没有这些页面的截图证明。共享构建登记和 m4d 清单需按仓库 AGENTS.md 交 Mika 排序；登记时存在 PR #33/TON-1067 的清单重叠，以及多个构建文件并行 PR。详见同目录 remaining-work.md，含李政亲手扫码的验收清单。

## 新增回调测试名

- `callerCancellationClosesListenerAndRemainsCancellation()`
- `timeoutIncludesConfigurationFetchAndCancelsItsHttpCall()`
- `simultaneousChallengesRejectEachOthersOriginAndRoute()`
- `browserLaunchFailureClosesListenerAndOffersQrLogin()`
- `configurationErrorsOfferQrLoginWithoutOpeningBrowser()`
- `malformedAndOversizedCallbacksLeaveCurrentChallengeUsable()`
- `callbackRejectsForeignMissingDuplicateOriginsAndReboundHost()`
- `timeoutWaitingForBrowserCallbackClosesListener()`
- `parentTimeoutIsNotConvertedToCaptchaTimeout()`
- `fullBridgeUsesCookieFreeConfigurationAndClosesCallbackAfterSuccess()`
- `browserCancelAndScriptFailureReturnActionableErrors()`
- `timeoutInterruptsStalledBrowserLaunchAndClosesListener()`
