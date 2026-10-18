# Task 1 — Linux 编译基线（desktop/source-validation-package-256-comment-geometry-compatibility @ 610218f8b）

结论：**通过**（排除 3 个 Windows 专用任务、使用仓库外 Python 路径垫片和 2 个 build 目录桩文件、命令行加大 JVM 堆/栈后）。未修改任何仓库文件。

## 环境
- Debian 13, Temurin JDK 21.0.12.1+1（packages.adoptium.net apt 源），Python 3.13.5（用户批准代替 3.12），Gradle 9.5.0（gradlew 官方分发）。
- 计划：先由 Grok Build 只读出计划（/workspace/grok-g-plan.md），确认不越界；本任务无代码改动。

## 命令（均在仓库根目录）
```
export PYTHON_EXECUTABLE=/tmp/python-strip-winpath.py PYTHONDONTWRITEBYTECODE=1 PYTHONIOENCODING=utf-8 GRADLE_OPTS=-Xmx256m
/usr/bin/time -v ./gradlew -p desktop --console=plain --no-daemon --max-workers=1 --no-parallel \
  -Pkotlin.compiler.execution.strategy=in-process \
  "-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8" \
  compileKotlin -x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker
# compileTestKotlin 同上，jvmargs 另加 -Xss16m
```

## 结果
| 步骤 | 结果 | 墙钟 | /usr/bin/time 最大 RSS | 采样 Gradle java 进程合计 RSS |
|---|---|---|---|---|
| compileKotlin（成功那次，151 个生成任务已 up-to-date） | BUILD SUCCESSFUL in 4m18s，455 条 warning，0 error | 4:19 | 7,271,224 KB (~6.9 GiB) | ~7.0 GiB |
| compileTestKotlin | BUILD SUCCESSFUL in 1m36s，98 条 warning | 1:37 | 3,573,876 KB | ~3.4 GiB |

总耗时说明：生成器冷跑分在前几次尝试里（1m18s + 2m47s），加上成功的 4m18s 和 1m36s，冷启动到两步全部通过约 10 分钟纯构建时间（不含失败重试）。

## 排除的任务（-x）
| 任务 | 原因 |
|---|---|
| prepareNativeDiagnosticShare | 用 MSVC cl.exe + Windows SDK 编 bilipai-diagnostic-share.dll，脚本非 win32 直接报错 |
| fetchJsWorkerJdk | 下载 Windows 版 Temurin JDK zip（固定 SHA）供 JS worker 用 |
| prepareJsWorker | 依赖上一项，用 javac.exe 构建 JS worker，产出 DESKTOP_JS_WORKER_CATALOG_SHA256 |
（以前在这台机器上排除过的 prepareJsWorkerResources 不在 compileKotlin/compileTestKotlin 依赖图里，这次没排除。）

## 需要披露的变通（全部在仓库外或 build 输出目录，未提交）
1. **Python 路径垫片**：`/tmp/python-strip-winpath.py`（sh 包装器）+ `/tmp/pyshim/sitecustomize.py`（13:52 云电脑重置后 /tmp 被清空，已原样重建到 /workspace/g-logs/python-strip-winpath.sh 和 /workspace/g-logs/pyshim/）。约 30 个生成器无条件给路径加 Windows 长路径前缀 `\\?\`（不判断 os.name），Linux 上会读写错误位置；垫片只在 pathlib.Path/PurePath 构造时去掉该前缀。通过 PYTHONPATH 加载，生成器用 sys.executable 起的子进程也会继承。
2. **build 目录桩文件**（被排除任务的输出，Kotlin 源码引用它们）：
   - `desktop/build/generated/native-diagnostic-share/kotlin/.../DesktopNativeDiagnosticShareAssetHash.kt`：内容与生成器写法一致，值取仓库内已审核的 pin `8d256ff3…091df`（desktop/native/diagnostic-share/approved-development-build.json）。
   - `desktop/build/generated/js-worker/.../DesktopJsWorkerAssetHash.kt`：**全零哈希**，仅供编译；真实值只能在 Windows 上生成。
3. **JVM 参数（命令行，未改 gradle.properties）**：desktop/gradle.properties 默认 -Xmx4g，compileKotlin 在 Linux 上 OutOfMemoryError → 改为 -Xmx6g。compileTestKotlin 在默认线程栈下 Kotlin 后端 StackOverflowError（协程变换，DesktopUpdaterIntegrationTest.packagedUpdaterEndToEnd，内部编译错误，不是源码错误）→ 加 -Xss16m 后通过。Windows 云端是否有同样需求未知。

## 证据分类
- 已在 Linux 编译通过：desktop compileKotlin、compileTestKotlin（上述条件下）。
- Python 检查通过：见 task2。
- JVM 测试通过：本任务未跑测试。
- 未验证：Windows 云验证、原生 DLL、JS worker、打包、更新器、界面、GPU、音频。

## 补充
- 13:51–13:52（伦敦时间）左右这台云电脑被重置，build 目录（包括这两个桩文件和所有编译产物）、/tmp、Temurin JDK 和 ~/.gradle 缓存都被清掉了。上面的编译结果是在重置前拿到的，日志保存在 /workspace/g-logs/t1-*.pass.log 和 .pass.time。之后要再编译，得先重装 JDK，重建桩文件。
