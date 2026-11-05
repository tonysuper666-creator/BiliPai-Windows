M4d ordinary-test repair, based on `57133abda` and dependent on PR #31 / #57.

The goal is to remove the 15 M4d failures without changing retained upstream
files, weakening business assertions, or reverting upstream UI decisions.
The retained production code, retained tests, and pre-change Windows JUnit XML
are the primary evidence. Windows acceptance must use the same fixed JDK,
BelowNormal priority, two workers and command as Scout's Sponsor verification.

This patch is **not runtime verified**. Five correction paths are absent from
`correctedUpstreamTestPaths` in `desktop/build.gradle.kts:3372`. The issue
explicitly reserves that file for Root. Root must register the paths below
before a Windows build can exercise these corrections. Production patches are
discovered automatically; test corrections deliberately are not.

Register under `app/src/test/java/com/android/purebilibili/feature/`:

- `settings/AppBuildVerificationPolicyTest.kt`
- `space/SpaceTabChromePolicyTest.kt`
- `space/SpaceLoadPolicyTest.kt`
- `space/SpaceAggregatedModelMappingTest.kt`
- `space/SpaceDynamicNavigationPolicyTest.kt`

Each correction pins the complete original source SHA-256. Test method names
remain unchanged so pre/post JUnit identities can be compared directly. Two
method names still describe the superseded upstream behavior; the assertions
and patch reasons explain the current behavior.

| Failing method (class abbreviated) | Classification and repair | Primary evidence at base SHA |
| --- | --- | --- |
| ChatStructuredMessages.sessionTipJoinsTextWithoutExposingPayload | Upstream defect: reject non-string JSON primitives, preserve quoted and structured text. | `ChatStructuredMessages.kt`, `parseChatTip`: primitive content was returned regardless of `isString`; original test explicitly rejects `invalid`. |
| ExternalMediaDanmakuMapping.mapsModeTextTimeAndColorIntoEngineItems | Upstream defect: select hexadecimal parsing by `#` prefix, decimal otherwise. | `ExternalMediaDanmaku.kt`, parser documentation specifies decimal integers, but length-based branch parsed `16711680` as hexadecimal. |
| AppBuildVerificationPolicy.returns verified when immutable release digest matches local apk | Follow upstream wording; retain VERIFIED, digest and attestation checks; also assert immutable state. | `settings/update/AppBuildVerificationPolicy.kt`: verified branch says 发布已锁定, not Release 已锁定. |
| AppBuildVerificationPolicy.returns unverified when release is mutable | Follow upstream wording; retain UNVERIFIED; assert mutable flag and warning. | Same file: matching provenance plus mutable release returns UNVERIFIED and says 仍可修改. |
| AppBuildVerificationPolicy.returns likely verified when mutable release digest matches without embedded provenance | Follow upstream wording; retain LIKELY_VERIFIED and hash check; assert mutable flag and warning. | Same file: digest-only mutable release branch says 仍可修改. |
| AppBuildVerificationPolicy.describes matched immutable release with attestation | Typography drift: compare full subtitle after whitespace normalization. | Same file, `resolveBuildFingerprintSubtitle`; all words, digest, locked release and provenance remain required. |
| SpaceLoadPolicy.keeps PiliPlus space tag contract | Invalid upstream fixture: supply profile name and avatar; preserve both original tag and location assertions. | `SpaceLoadPolicy.kt:579` rejects blank name/face before filtering location/real_name tags. |
| SpaceLoadPolicy.detects cheese tab in tab2 | Invalid upstream fixture: supply avatar; preserve original cheese assertion. | Same early-return guard; valid tab payload was unreachable. |
| SpaceAggregatedModelMapping.maps core header and seeded content | Follow upstream primary rail simplification; retain all seeded library-content assertions. | `SpaceProfileEnhancementPolicyTest`, primary-destination test independently requires HOME/DYNAMIC/CONTRIBUTION and optional CHEESE. Existing aggregation checks still require favorites and bangumi data. |
| SpaceDynamicNavigationPolicy.opensDynamicDetailWhenArticleJumpUrlIsOpus | Follow shared upstream article routing; require OpenArticle in both shared and space actions, with exact ID/title. | `dynamic/components/DynamicCardClickPolicy.kt`, article-major branch; `SpaceDynamicNavigationPolicy.kt` delegates it. Separate direct opus link tests continue to require OpenDynamicDetail. |
| SpaceTabChromePolicy.main tab chrome resolves selected index and keeps touch target height | Follow upstream glass-enabled main rail and shared height token; retain selection/drag/geometry checks. | `SpaceTabChromePolicy.kt`, `resolveSpaceMainTabChromeSpec`, uses AppChromeSizeTokens and enables glass. |
| SpaceTabChromePolicy.contribution tab chrome prefers selected id and uses compact secondary chrome | Follow upstream non-glass contribution rail; retain selected ID, scrollability, width, padding and indicator constraints. | Same file, `resolveSpaceContributionTabChromeSpec`, disables glass and drag selection. |
| SpaceTabChromePolicy.contribution tab chrome keeps drag selection for three contribution entries | Follow upstream disabled contribution drag; preserve non-scrollability and long-title width checks. | Same contribution function disables drag regardless of item count. |
| SpaceTabChromePolicy.secondary switch caps longest title to three visible slots | Use shared control/indicator tokens instead of old 48/30 values; preserve width cap and all viewport/scroll assertions. | Same file, `resolveSpaceSecondarySwitchChromeSpec`, uses BottomBarMatchedSegmented tokens. |
| SpaceTabChromePolicy.contribution toolbar uses compact chrome and icon first actions on narrow phones | Check shared liquid-indicator geometry against rail height; retain compact controls, icon-first and discoverability assertions. | Same file, `resolveSpaceContributionToolbarSpec`, calls `roundMatchedLiquidIndicatorHeightDp(40f)`. |

`DesktopM4dPayloadRegressionTest` additionally covers bare JSON numbers/booleans,
quoted text, mixed structured content, six/eight digit decimal colors, explicit
RGB/ARGB colors and malformed input. It calls the actual migrated functions.

Validation performed: seven patches pass `git apply --check`; all five test
source digests match; all corrected test method identities are preserved;
assertion counts do not decrease; no Ignore/Disabled annotations were added.
These checks establish patch applicability only, not runtime correctness.

Pending acceptance (run once after Root registration):

```text
gradlew.bat <fixed-toolchain> -p desktop :test sponsorPresentationTests --continue --max-workers=2 --console=plain -x upstreamAppCoreSourceTests -x upstreamDesignSourceTests -x upstream-core:test
```

Require all 15 targeted identities green, no new failing identities, and passing
`verifyUniqueClassNames`; attach pre/post XML and the comparison. Landing:116,
the known timeline timeout, Windows-only Storage/Video-CDN, upstream app-core
tests, packaging and CI timeout remain outside this slice.
