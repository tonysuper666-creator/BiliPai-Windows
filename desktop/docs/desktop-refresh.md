# Desktop page refresh

`AdaptivePullToRefreshBox` is patched at build time to host a corner button and its content in the same `DesktopRefreshReserve`. The desktop pull gesture stays disabled. F5 and Ctrl+R select the visible button through the shell registry and invoke its click path.

Lists inside that host use the real `LazyListState` or `LazyGridState` callbacks registered by the desktop patch of `AppScrollableLists`. Their bottom content padding is at least the corner button's 76dp reserve; existing larger insets are preserved. Refresh invokes those states directly. The Home pager and desktop Dynamic feeds retain their explicit state callbacks. The button's production path does not dispatch synthetic AWT wheel events.

Each button invocation creates a separate operation. `desktopRefreshLaunch` captures it before launching into a retained ViewModel scope, and the existing owned Call.Factory attaches it before OkHttp dispatch. Responses report complete body reads, top-level JSON business status and Retrofit decoding to that operation. Async conversion runs within its initiating request context. Request ownership, account admission, cancellation and transport identity continue through the existing wrappers. Closed operations and unrelated requests do not contribute to a later refresh.

The transitional app producers use `tools/desktop_refresh_adaptation.py` for scoped launches and shared list imports. Only refresh content adopts the shared lists; the helper is an input of the existing producer tasks. No retained upstream source is edited. The design-system adaptations use the existing M0 unified patch mechanism, and hot search's legacy output uses a transition patch.

When M4b removes a producer, carry its shared list import and scoped launch binding into the page/ViewModel's desktop patch. Preserve the native message admission context, owned Call.Factory wrappers and response converter. The shared design-system patches continue to apply directly; they fail if an upstream hunk drifts.

Regression coverage includes the QA nested-code and late-body-disconnect reproductions; Category, Inbox and Trending content layout scenes; state callback return to the top; toast and retry; asynchronous decoding; and separation from unrelated/previous requests. The layout scenes mount the actual list components with the generated pages' inset shapes, rather than the full page ViewModels.
