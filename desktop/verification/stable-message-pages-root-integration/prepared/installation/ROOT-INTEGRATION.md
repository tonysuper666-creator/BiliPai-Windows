# Executable message Root installation supplement

Pinned final input is Candidate `01e5d615fa48da8fc73c470ea452f885f51af454`, read through `git show`, never the concurrently edited working tree. This includes the four Bangumi pages and the retained imageShare provider correction. The original frozen packet remains unchanged at `stable-original-message-pages-root-parity`; its contract SHA is `32bf55205f093564d37444ead049f130f2cc4f3395a37b78fd2bf73d1b17ee9b` and handoff SHA is `27a644ddf45fea069b47b601d0728234118561110290aac4ddec5f3800a90ffd`.

## Small installation operations

`root-install-contract.json` describes five exact-hunk targets, four new-file copies from the old frozen packet, and a verified source-registry union. The five existing targets are Shell, ReadyOriginalRootMount, Gradle, DesktopRepository and DesktopCommunityRepository. Do not copy any full file from `prospective/` or the frozen packet's prepared data copies: those are explicitly declared compilation overlays. The installer applies each hunk to verified current bytes, computes the expected output, and verifies every target before its first optional write. LF normalization is explicit in baseLfSha256; unrelated source text is retained. No Gradle, push, release, account or workflow action is performed by the installer.

The thirteen new source identities and four existing feature unions retain all unrelated sources, resources and header fields. At the final baseline this is **1206→1219 sources**, with the same 244 resources. `registry-delta.json` names each append/union; these counts are identity accounting, not feature acceptance. Existing modes and hashes must match; the original four pure files have sole producer `prepareUpstreamSources`. The thirteen selected/full original bodies have sole producer `extractOriginalMessagePages`, without `--standalone`. The original Home unread functions, BGM error helper and share-v2 user types/loader keep their existing producers.

Run read-only validation from Main:

```powershell
python desktop/.local/stable-original-message-pages-root-installation/install.py
```

After Root has reviewed the contract and is on its exact Candidate HEAD, apply with:

```powershell
python desktop/.local/stable-original-message-pages-root-installation/install.py --apply --contract-sha ea34b9c47aaab1660e815e638589673d2d389fac914c310f4e9b5990954907a3
```

The default mode checks current Candidate without writing. `baseline-install-proof.json` validates this exact final working tree, with zero writes. Root must rebase before applying if another integration changes HEAD or targeted bytes. The helper can prepare a **new sibling lane** without altering this frozen supplement:

```powershell
python desktop/.local/stable-original-message-pages-root-installation/prepare.py <new-committed-HEAD> desktop/.local/stable-original-message-pages-root-installation-rebase-<short-HEAD>
```

Then invoke the original installer with `--contract <new-lane>/root-install-contract.json`. Review its new hunks and union before applying. Context application is unique and exact, not fuzzy. If an expected original context changes, preparation fails closed. The new message branch anchors directly before CommentDetail, preserving the installed Bangumi leaf above it. The complete existing `services.originalVideoWindowContent` body, including `LocalDesktopImagePreviewShareBindings provides environment.gallery.imageShare` outside the Comment wrapper, is unchanged; `protected-consumers.json` records its exact span identity. The dependency producer appends after the latest complete Gradle task rather than replacing a build file. Never rename this baseline's compile/UI evidence to a newer snapshot.

## Actual consumer and ownership chain

`DesktopReadyOriginalRootMount` creates `rememberDesktopOriginalMessagePagesRoot(repository,community,routes,desktopDetailRenderEffectsSupported())` once beside the real root/routes, above individual visible leaves. Its same `DesktopReadyOriginalRootHandle` retains the owner. The existing leaf callback gets one tail argument, `messagePages`; Shell sends the exact Inbox/ReplyMe/AtMe/LikeMe/SystemNotice/Chat keys to `DesktopOriginalMessagePageRootHost`. The old Community MESSAGES fallback is removed. The existing CommentDetail leaf and its sole typed `desktopOriginalOpenMessageLink` dispatcher are preserved. No second navigation stack, Store/client/HTTP pool or account authority is introduced.

RootStack itself needs **no hunk**. It calls `leafContent(key,routes,active,pagerHosted)` for these keys inside its existing original NavDisplay and saveable entry. Actual Home/Profile `onInboxClick` calls `commands.push(Inbox)`. The Shell legacy section action also emits Inbox. MainHost selection uses **original** `resolveBottomPagerPageForRoute(key.toLegacyRoute(), visibleItems)`, not `desktopReadySection`: the original `BottomNavItem` enum has no message item. All six message key types therefore return null and go through `RootRouteAssembly.pushAdmitted` into the physical Nav3 stack. Inbox owns the real Inbox entry, not a simulated physical Home entry. BottomBar has no standalone Inbox item in v0.2.3; its Profile/Home message actions use the same typed path.

Inbox→Reply/At/Like/System/Chat callbacks push their exact keys and parameters. Cover keeps Inbox's owner and NavEntry saveable state; returning pops only the child. Removing an entry prunes and cancels its original child jobs; owner keeps retired scopes until drained. Same-MID account replacement changes captured epoch and rejects old calls/commits. Each request and post-IO publication retains real caller Job, root/entry presence, refresh generation and epoch/MID admission. Existing short lock order is SessionStore→Root gate→message state; no HTTP, file read or join occurs under those locks. Mutable POST bodies are one-shot with the actual Community retry(false) transport, preserving original bytes and explicit business retries.

## Restore, shutdown and account handoff

The single existing handle closes physical route admission, then awaits message `closeAndJoin`, then existing personal pages/retainer before global Window resources. RootMount's epoch-install effect closes and drains the previous message owner before rebuilding the physical stack or installing the next account. Dispose closes admission synchronously and lets the existing external shutdown task drain the same handle. No child joins itself.

Shell already awaits this same `homeRootRef.getAndSet(null)?.closeAndJoin()` in all three paths: Runtime beforeStoreFreeze, Backup beforeRestore, and registered application shutdown. Adding the handle's message drain automatically connects all three; do **not** add a second Main shutdown coordinator or a leaf Compose-scope restore task. `Main.kt` and `DesktopOriginalRootStack.kt` remain unchanged and their pinned consumer hashes are recorded in the contract.

## Fixed actual dependencies and provenance

The producer adds exactly two fixed coordinates:

```kotlin
implementation("org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-rc01")
implementation("org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0-rc01")
```

The desktop publications were fetched from official Maven Central, with preserved POM/JAR URLs and raw hashes in the frozen packet's `dependencies/identities.json`, also copied into this contract. Apache-2.0 is declared in the POMs. JVM adaptive-desktop JAR SHA is `94807e7afab0cb0d455a32ebbc3f07efe7a495655f3abc40f1c13ad40726a9d2`; adaptive-layout-desktop is `0b6a510201508aa3b4c6d15a1f40a9b087f97cdcecf625a1638445b920ec5699`; transitive window-core-desktop 1.5.0 is `e6a2c3d4e6dae7f69e466d875c1e8ab78734af991ac33e2076a99c77665dd330`. All three POM hashes and exact URLs are in the machine contract. Original Android used adaptive 1.3.0; this actual JetBrains JVM port is 1.3.0-rc01, a stated compatibility difference. Existing selected Compose 1.12.1 remains authoritative. Root should retain the notices/provenance in its normal packaged dependency-notice inventory.

## Normal Root verification commands

After installation, run the **normal desktop Gradle test task**, not this prospective compiler or a replacement classpath. Existing toolchain paths are:

```powershell
$env:JAVA_HOME = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain/jdk/jdk-21.0.12.1+1'
& 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain/gradle-home/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat' `
  --no-daemon --console=plain `
  -g 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain/gradle-home' `
  -p 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai-v023/desktop' `
  test --tests 'com.bilipai.desktop.ui.DesktopOriginalMessagePagesTest'
```

Require XML `desktop/build/test-results/test/TEST-com.bilipai.desktop.ui.DesktopOriginalMessagePagesTest.xml` to contain **14 discovered test methods, zero failures/errors/skips**. These are the same 14 actor/HTTP bodies already executed in frozen packet run18: true original pagination/retry/cursor/read/delete fields, Chat ACK/control/group/top/DND/batch, original text/image/withdraw bodies, 503 single submission, image cap, epoch, cancellation and cover. They use task-owned synthetic account/temp Store and exact allowlisted loopback transport. No live account request is authorized by a test run. Framework discovery/execution has not yet been claimed.

After the normal producer ran, `verify-generated.py --candidate <Candidate>` verifies all thirteen actual generated byte hashes and the four single-producer synced pure source identities; shared output must not contain duplicate DIRECT files.

Final supplement `compile-runs/04` compiled 23 explicit original/platform/data/Root inputs against immutable **actual87**'s 101 pinned runtime entries plus three fixed JVM libraries, with copied immutable compiler inputs. Actual87 manifest SHA is `5aecc4d5a84f83368d01820541fb2beb8b3cf29ececefa17e8a9363ae030bb03`; ordered CP SHA is `cf3e0773efdb82b4b4c9fa19654a54b92c16401b675cd746748de8b41141361f`. It does not put the old 85 prospective JAR on its compilation classpath. Earlier `compile-runs/03` and `policy-proof-02` remain explicit historical actual86 evidence. The latter executes actual86 original pager/key/back/saveable policies, with five loaded actual class pins and zero production overrides: six message key types across four real visible-tab configurations never map into MainHost pager. This proves those policies and source wiring, **not actual Root screen retention/scroll or whole-window acceptance**. The first policy fixture's wrong expected producer filename is preserved in `policy-proof`; actual compiled producer is `DesktopOriginalRootPagerPolicyKt`.

Full integrated Root/NavDisplay message UI, wide MessageCenter/full Chat, native chooser, actual unread/poll/service behavior, real account reads/writes and packaged EXE remain for Root acceptance. The old packet's two light-style pointer workflows remain historical prepared-overlay evidence on actual85; they are not relabeled actual87 or whole-Root proof. No completion percentage is supplied.
