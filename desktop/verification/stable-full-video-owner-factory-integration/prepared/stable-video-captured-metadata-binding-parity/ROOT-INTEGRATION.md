# Captured metadata facet — source only

Apply `local-hunks/binding.json` to the existing sole
`DesktopOriginalVideoRepositoryBinding.kt`; install neither the baseline nor
the prepared whole file as an overwrite. Four exact hunks only. The capture
function and constructor ABI are unchanged. Actual67 LF base is
`81711670c1808f81291e191bc68b4e4516c81d0dfa86ae5a2a286d41c40cd536`,
candidate is
`22d033abfd1b02e65227107ab924a5e8e64f64a55ac7ee003edc63db04374049`.

Apply the one exact `local-hunks/repository-visitor.json` accessor hunk to the
existing Repository. It reads the actual `@Volatile visitorInitialized` and
`visitorGeneration` under the existing SessionStore admission; no new bootstrap
state, activation claim or initialization algorithm is introduced. This hunk
is source-only and was not a second Repository class override in the narrow
compile. Parent's sole core/metadata packet owns `getWbiKeys` visibility;
reference/apply that packet once and do not reproduce its protocol or producer.

The parent owns all metadata protocol bodies, environment and extended owner
repository assembly. In that factory, capture the Binding inside the real
load/quality request coroutine exactly as before, then construct its environment:

```kotlin
val metadataEnvironment = DesktopOriginalVideoMetadataEnvironment(
    load = binding.environment,
    protocol = binding.protocol,
    hasPrimarySession = binding::hasPrimarySession,
    hasPrimaryCsrf = binding::hasPrimaryCsrf,
    hasPrimaryBuvid = binding::hasPrimaryBuvid,
    hasPrimaryAccessToken = binding::hasPrimaryAccessToken,
    isBuvidInitialized = {
        binding.isBuvidInitialized { receipt, owns ->
            repository.ownedHomeVisitorInitialized(receipt.accountEpoch, owns)
        }
    },
    updatePrimaryVip = binding::updatePrimaryVip,
)
```

The extended request repository's `primaryApi` and `playbackCalls` getters forward
to this Binding's admitted getters. The former is the same owned primary Retrofit
service already used by raw loads. The latter is the existing
`ownedPlaybackCallFactory(authorization, ::current)`, preserving captured
authorization and request/entry Job; no client/Store/cache/transport is created.
Never retain this request repository after its invocation Job completes or
replace it with a latest-credential page-wide service.

The original primary presence checks use non-empty primary `SESSDATA`,
`bili_jct`, `buvid3` and access token values from the existing Repository/Store.
The selected playback account cannot supply or overwrite these primary flags.
Bootstrap presence is a required query with no default; only this metadata
caller supplies it, so old non-metadata capture callers need no new argument.

VIP writes use the captured primary MID and the existing encrypted
`saveProfileVipStatus` under outer receipt Store → entry admission. That Store
currently includes `isVip` in playback authorization identity. A changed VIP may
therefore retire this request's receipt: the full load must cancel/recapture at
its original request entry and must not silently retag/adopt the old Binding.
The Store policy is unchanged in this packet. Root/parent must resolve this
explicit integration boundary before claiming a complete VIP-refresh load.

No network/native wait or join runs in admission. The original Store's existing
synchronous VIP persistence remains its authority. Do not turn the presence
or VIP views into another state/credentials snapshot cache.

Narrow compile `runs/01` passed: one existing Binding family explicitly overridden,
23 candidate classes, actual67's immutable 101-entry graph checked before/after.
No metadata class/model/protocol was produced or overridden. No runtime, HTTP,
account, GUI/HWND or native action was executed. Root whole-source compilation
and actual factory behavior remain pending.
