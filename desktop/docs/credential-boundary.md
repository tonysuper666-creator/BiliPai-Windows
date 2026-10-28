# Desktop credential boundary

The desktop network adapter owns this security fix. Upstream source archives and
their tests remain unchanged.

`tools/patch-public-content-credentials.py` runs after `extractUpstreamPlugins`.
It replaces one generated statement in `PublicContentNetwork.kt` with a call to
`removeDesktopCredentialQueryParameters`. The helper matches decoded parameter
names but copies retained query components in their original encoding and order.
This removes aliases such as `%61ccess_token` without rewriting public article
IDs, duplicate parameters, empty values, or signed public URL encoding. The
generated receipt records the original and patched hashes and the exact hunk;
the build fails if the anchor changes. The original eight JVM tests compile and
run against the patched production source.

The shared `DesktopRepository` network interceptor calls
`enforceDesktopCredentialBoundary` after cookie overrides and media headers.
It checks the actual destination on every redirect, using the existing Bilibili
service/CDN host gate with a dot-delimited subdomain match. Requests outside
that gate lose Bilibili account parameters, userinfo and account cookies (including
CookieJar, forced-cookie and explicit media-cookie values). Unscoped bearer/token
headers and query parameters are also removed. A registered media origin may keep
its own explicit Authorization/token headers and access/refresh-token URL fields;
that exception ends on cross-origin redirects. Account keys, CSRF and device fields
are still removed. Clean Referer values survive; query, userinfo or fragment causes
the Referer to be reduced to its origin.

The shipped `upos-hz-mirrorakam.akamaized.net` UPOS mirror has a separate media
exception: its signed URL's `buvid` and clean Referer survive, but Cookie,
Authorization and other account fields are removed. This does not trust arbitrary
`akamaized.net` tenants or lookalike domains. Public RSS/article/image clients remain anonymous on all
hosts, including Bilibili. This change does not rewrite request bodies or replace
the session store's HTTPS and cookie-domain rules.

## M0 direct-source migration

Keep `DesktopCredentialBoundary.kt` and the repository network-interceptor call
in the desktop platform layer when moving core-data/network-core to direct
compilation. For the public-content source, stage a build-directory copy of
the pinned upstream `PublicContentNetwork.kt`, apply `patch(source)` from
`tools/patch-public-content-credentials.py`, and compile that copy in place of
the original. Do not compile both copies or modify the upstream checkout. Retain
the patch receipt and original JVM tests. The script's current CLI expects the
existing plugin generator output and receipt; M0's staging task should call its
`patch` function and record hashes for the new staging layout.

When upstream changes the anchor, review its behavior before updating the patch.
Remove the patch only after the unchanged alias test and desktop wire tests pass
against the upstream fix. `DesktopCredentialBoundaryTest` uses only loopback
HTTP sockets and synthetic credentials; it verifies direct third-party requests,
lookalike domains, and Bilibili-to-third-party redirects through the production
repository interceptor, as well as anonymous public-client redirects. Media tests
also cover Akamai signed URLs, same-origin plugin authentication, CookieJar and
forced-cookie stripping, unsafe Referer normalization and cross-origin redirects.

Mac full verification uses TON-987's `verifiers.zip`:

```sh
MAC_VERIFY_REPO=/path/to/checkout bash mac-verify.sh d9595dc75
MAC_VERIFY_REPO=/path/to/checkout bash mac-verify.sh <fix-commit-sha>
```

Compare named failures as well as totals; the baseline has known failures.
These runs use the verifier's documented POSIX adapters, not Windows GUI proof.
