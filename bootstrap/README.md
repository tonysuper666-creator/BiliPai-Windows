# Linux 构建环境恢复（云电脑重置后 15 分钟内）

本目录只含环境脚本，不含仓库代码、令牌、密钥或个人信息。验证环境：Debian 13 (trixie) x86_64，16 GB 内存，
Temurin JDK 21，Python 3.13.5，Gradle 9.5.0（仓库自带 gradlew 下载官方发行版）。

> 证据边界：Linux 编译通过 ≠ Windows 云验证 ≠ 真机。下面两个桩文件只为 Linux 编译；依赖 JS worker
> 的测试在 Linux 上会报 `JS worker resource path is invalid`，属预期。

## 步骤（约 15 分钟）

| 步骤 | 命令 | 约耗时 |
|---|---|---|
| 1 装依赖 | `sudo sh bootstrap/install-deps.sh`（Debian apt + Adoptium 官方源：git、python3、time、temurin-21-jdk） | 3 分钟 |
| 2 取仓库 | `git clone https://github.com/tonysuper666-creator/BiliPai-Windows.git /workspace/bilipai-g`（凭据由用户自己配置，本目录不提供） | 2 分钟 |
| 3 行尾 | `git -C /workspace/bilipai-g config core.autocrlf false && git -C /workspace/bilipai-g config core.eol lf` | 即时 |
| 4 取本目录 | `git -C /workspace/bilipai-g worktree add /workspace/bootstrap origin/grokbot/linux-bootstrap`（或 `git show` 逐个导出） | 即时 |
| 5 桩文件 | `sh /workspace/bootstrap/bootstrap/make-stubs.sh /workspace/bilipai-g`（每次 `rm -rf desktop/build` 后重跑） | 即时 |
| 6 最小验证 | `/workspace/bootstrap/bootstrap/gradle.sh /workspace/bilipai-g compileKotlin` | 冷启动 6–9 分钟 |

日志默认写到 `/workspace/g-logs/gradle-<时间>.log`（`LOGDIR` 可改）。

**恢复成功的判据：** 第 6 步日志末尾有 `BUILD SUCCESSFUL` 和 `EXIT=0`，并且 `grep '^e: '` 无输出。

## 文件

| 文件 | 作用 |
|---|---|
| `install-deps.sh` | 只用 Debian 官方源与 Adoptium 官方源安装 JDK 21 / Python / git / time |
| `python-strip-winpath.sh` | 作为 `PYTHON_EXECUTABLE`；预加载 `pyshim/sitecustomize.py` |
| `pyshim/sitecustomize.py` | 去掉仓库生成器无条件加的 Windows 长路径前缀 `\\?\`、`\\?\UNC\`（只包 `pathlib.Path` 构造）。不加它，Linux 上生成器会在仓库根下写出名为 `\\?\` 的目录 |
| `make-stubs.sh` | 生成两个被排除任务的输出：①诊断 DLL 哈希桩，取仓库内已审核 pin `desktop/native/diagnostic-share/approved-development-build.json` 的 `dllSha256Bytes`（不从任何 DLL 计算）；②JS worker 目录哈希**全零桩，全零只为让编译通过，真实值只能在 Windows 上由 prepare-js-worker.py 生成** |
| `gradle.sh` | 完整 Gradle 命令行（见下） |
| `test.sh` | JVM 测试：`gradle.sh` + `test -x prepareJsWorkerResources --continue --rerun`，后接 `--tests ...` |
| `python-test.sh` | Python unittest，带垫片与 `desktop/tools` 的 `PYTHONPATH` |

## 完整 Gradle 命令行

```
PYTHON_EXECUTABLE=<bootstrap>/python-strip-winpath.sh GRADLE_OPTS=-Xmx256m \
./gradlew -p desktop --console=plain --no-daemon --max-workers=1 --no-parallel \
  -Pkotlin.compiler.execution.strategy=in-process \
  "-Dorg.gradle.jvmargs=-Xmx6g -Xss16m -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8" \
  -x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker \
  <tasks>                       # 跑测试时再加 -x prepareJsWorkerResources
```

为什么这样设：
- `-Xmx6g`：4g 时 compileKotlin OOM。
- `-Xss16m`：compileTestKotlin 的 Kotlin 后端默认栈会 StackOverflow。
- `--no-daemon --max-workers=1 --no-parallel` + in-process 编译：16 GB 机器上只保留一个 JVM，峰值约 7–8 GB。**同一时间只跑一个 Gradle。**
- 3 个 `-x`：都是 Windows 专用（MSVC 编译诊断 DLL、下载 Windows JDK、打 JS worker 镜像）。
- **不要** 排除 `prepareVeyraRuntimeVerifier`（R1 修复后它在 Linux 上可通过）。

## 已知 Linux 限制（不是回归）

- JS worker 相关测试（ui 包中约 3 个）因全零桩失败。
- Windows 专用测试（注册表、DXGI、PowerShell 等）在 Linux 上失败或跳过；判读见各 grokbot-reports。
- 冷编译（无缓存）：compileKotlin 约 6–9 分钟，compileTestKotlin 约 8–12 分钟。
