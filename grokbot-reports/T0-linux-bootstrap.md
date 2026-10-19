# T0 保全 Linux 构建环境

- 分支：`grokbot/linux-bootstrap` @ `7877e9a57`（孤儿分支，只含 `bootstrap/`，9 个文件，150 行）。
- 内容：`install-deps.sh`（Debian apt + Adoptium 官方源）、`python-strip-winpath.sh` + `pyshim/sitecustomize.py`（原样沿用的 `\\?\` 垫片，路径改为相对脚本位置）、`make-stubs.sh`（诊断 DLL 哈希取仓库 pin `desktop/native/diagnostic-share/approved-development-build.json` 的 `dllSha256Bytes`；JS worker 全零桩，明确注明只为编译）、`gradle.sh` / `test.sh` / `python-test.sh`、`README.md`（15 分钟恢复步骤与成功判据）。
- 不含令牌、密钥、邮箱或个人信息（已用 rg 扫描 ghp_/gho_/github_pat/token/password/secret/@）。

## 验证
- `make-stubs.sh` 在临时假仓库中生成的两个文件与此前实际使用的桩逐字节一致（JS 桩仅注释行措辞不同）。
- 垫片：`python-strip-winpath.sh -c "pathlib.Path('\\\\?\\/tmp/x')"` 输出 `/tmp/x`。
- 端到端：在 89a0aee85 上 `rm -rf desktop/build` → `make-stubs.sh` → `gradle.sh . compileKotlin compileTestKotlin --no-build-cache`：BUILD SUCCESSFUL，6m22s，0 错误（日志见 verify-89a0aee85 报告）。
- `install-deps.sh` 只做了语法检查（`sh -n`），没有在全新机器上重跑；它与本机实际安装方式（Adoptium trixie 源 + temurin-21-jdk）一致。

## 证据分类
Linux 编译：已验证（上述端到端）。Python：垫片已验证。JVM：不适用。未验证：全新重置机器上的完整 15 分钟流程、install-deps.sh 实际执行。
