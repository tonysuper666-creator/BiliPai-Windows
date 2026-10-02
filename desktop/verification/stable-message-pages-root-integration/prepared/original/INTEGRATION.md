# Complete v0.2.3 message seven pages, prepared only

Base Candidate is clean `6ac84c8036ed4c75e2944d92d1020566e283e983`. Immutable actual product 85 is unchanged. The full original Inbox/Chat VM and MessageCenter/Inbox/ReplyMe/AtMe/LikeMe/SystemNotice/Chat Screen bodies are kept, with explicit Windows/entry/caller binding substitutions. The original message repository body is a thin injected protocol, borrowed by the existing Community owner. This package changes no Candidate file, Gradle, credentials or HWND.

## Install only these operations

`install-contract.json` lists four new copy targets (generator, two platform files, one 14-method JUnit file) and two narrow data hunks. The large prepared DesktopRepository/CommunityRepository copies exist ONLY as declared prospective compile inputs; apply `DesktopRepository.patch` / `DesktopCommunityRepository.patch`, never copy those full files. Recheck the latest hunk anchors and inverse if Root has since changed a file. Repository gains one optional `transport: OkHttpClient = client` tail argument; the same tagged Jar/epoch admission is retained. Community passes its existing retry(false) client and existing device-id authority. There is no new HTTP client/pool, session, credential store or repository cache.

Union the 17 source entries from source-inventory.json into the current registry; preserve every current entry and its features. Four direct pure policies go through the existing source-sync lane. MessageCenterPolicy remains policy-extract: the installed Home producer owns its two unread functions, so this generator emits only the remaining original types/functions. Installed BGM owns MessageFeedError; the new original MessageFeedCommon excludes that one function. Installed share-v2 owns UserBasicInfo/user resolver/loader; this lane references them without producing duplicates. Keep the existing MessageApi/response DTO inventory unchanged.

Run the new producer with `--repo <Candidate> --out <build/generated/original-message-pages>` without `--standalone` in the shared build, and compile that output once. `--standalone` was used only to include four original pure source files for isolated proof. Existing inherited helpers locate Kotlin blocks; no network is involved in generation.

## Fixed actual JVM dependencies

Add `org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-rc01` and `org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0-rc01`; their JVM variants and `org.jetbrains.androidx.window:window-core-desktop:1.5.0` are fixed in dependencies/identities.json (official Maven Central response/POM/JAR bytes). All POMs declare Apache-2.0. Existing selected Compose 1.12.1 wins over their 1.10.0 transitive floor. The original Android dependency is 1.3.0: this is the real JetBrains JVM port 1.3.0-rc01, not a silent claim of identical Android library bytes. The API calls and original SupportingPaneScaffold body compile unchanged. No Android APK task or new shell-build invocation was performed.

## Actual Root hooks

Create once INSIDE the actual retained Root composition but OUTSIDE its individual visible leaf switch:

```kotlin
val messagePages = rememberDesktopOriginalMessagePagesRoot(
    repository, community, routes, desktopDetailRenderEffectsSupported())
```

It reads the same `routes.root.environment` and uses the already installed `desktopOriginalOpenMessageLink` typed dispatcher with captured NavKey/source route. It does not read a missing leaf ambient during Root creation. Remember keys are the actual Repository/Community/routes/environment owner; no root DTO or navigation mirror is introduced. The current public-within-module signatures are:

```kotlin
@Composable internal fun rememberDesktopOriginalMessagePagesRoot(
    repository: DesktopRepository, community: DesktopCommunityRepository,
    routes: DesktopOriginalRootRouteAssembly,
    supportsRenderEffects: Boolean): DesktopOriginalMessagePagesRoot

@Composable internal fun DesktopOriginalMessagePageRootHost(
    key: BiliPaiNavKey, owner: DesktopOriginalMessagePagesRoot,
    routes: DesktopOriginalRootRouteAssembly, active: Boolean)
```

Route the exact Inbox/ReplyMe/AtMe/LikeMe/SystemNotice/Chat NavKeys to Host; keep CommentDetail on its already installed independent original owner. Replace the old CommunityMessages branch only for those six original key types (Inbox contains MessageCenter+Inbox). Keep the existing `LocalDesktopOriginalMessageLinkNavigation`, actual foreground/displayability provider, global timeline/Home-card preference providers, and original BackHandler provider around each leaf. These supply the already existing scaffold/skeleton/blur/store behavior. Guest Host calls the current CommunityLoginGate and actual Login key rather than constructing a guest private-message VM.

Each entry owns child coroutine lifetimes and original VMs; original list/scroll state stays in the existing saveable retained NavEntry composition. Cover keeps the entry; `snapshotFlow(routes.stack)` prunes removed entries. Parent actual Root gate/captured epoch/MID, caller Job, refresh generation and entry presence gate every network call and final state commit. Mutations are admitted only from a visible page, and accepted requests stay tied to that caller/entry after cover. Root gate lock order remains SessionStore -> Root gate -> short message state lock. No HTTP/body/file wait or job join occurs in these monitors. Cancels of obsolete list/more jobs run outside message state lock. Preserve Home/root job cancellation and joins during restore/shutdown; also call `messagePages.close()` at Root retirement and `closeAndJoin()` from the existing external beforeRestore/shutdown drain (never from a child/under Store monitors). The composable disposes by closing as a fallback.

Full Chat and wide-center use child original ChatViewModels and retire the previous embedded Chat when selection closes/changes; full-screen Chat entries remain retained until removed. Removed entries and replaced pane Chat scopes remain tracked until their jobs finish, including close() followed by closeAndJoin(). Close serializes retirement publication with entry creation; pane creation checks ownership inside its short monitor. Revision publication uses ConcurrentHashMap because the existing Jar interceptor checks captured caller tickets on another thread. No network or job join runs under those monitors. Windows image selection is user-triggered JFileChooser only, captures the originating entry and checks visibility after return. It reads only the chosen regular nonsymlink file with the original 15 MiB limit; it does not scan folders or request gallery permissions. Real native chooser remains untested.

POST bodies are exact original bytes/type/length with isOneShot=true. This prevents OkHttp implicit 503 Retry-After:0 and redirect replay, in addition to the Community retry(false) transport; it does not remove the original Inbox duplicate-cursor business retry. UI writes never happen during construction/composition. Existing initial Chat ACK/SystemNotice cursor updates are original read-page behavior, not synthetic tests against a real account.

## Evidence and remaining acceptance

`runs/11` compiled all 21 explicit prospective inputs against actual 85's 101 ordered/pinned CP entries plus three fixed JVM dependency JARs. `fixture-runs/18` executed 14 meaningful original protocol/VM cases over exact allowlisted task-owned loopback HTTP, using the actual Community factory with a disclosed fixture-only replacement of its private transport field. Synthetic credentials are confined to a temporary Store. Twelve loaded-class identities prove the actual prospective class origin; no second VM/store/renderer implementation is used.

`fixture-runs/19/ui` contains two light-theme M3/Miuix actual original Inbox->Reply Screen workflows, eight physical ImageComposeScene pointer events, four PNGs, semantics and typed callback values. These fixtures explicitly supply task-scene foreground and the real original setting adapters on a temporary shared global backing. This is prepared-overlay UI evidence, **not whole Root/NavDisplay acceptance**. Historical failed fixture runs remain preserved; their missing required fixture ambients were corrected without modifying production scaffold/skeleton. Run14's overbroad image cap assertion counted original post-send refresh/ACK reads; the final test asserts no extra upload/send POST while allowing those original reads to finish.

The 14-method JUnit file is independently compiled; its same bodies were executed by the above standalone actor/HTTP fixture. Main JUnit framework execution is Root's next step and is not claimed here. After Root normal compilation, recheck integrated route/retained-entry/restore hooks. Full wide SupportingPaneScaffold and full Chat UI, actual clipboard/emote/image chooser interactions, real account service responses, live account writes and packaged EXE acceptance remain unverified. HTTP412 cannot be treated as feature acceptance. No overall completion percentage is supplied.
