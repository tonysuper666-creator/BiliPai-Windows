# BiliPai private Veyra component verification

This is the first SR/HDR backend candidate. NR, DLSS, and VFG remain pending.
It verifies selected local component material. It does not load any feature DLL,
initialize NGX, run GPU work, or prove that an enhanced frame reached the display.

The unselected template keeps projectId, moduleBuildSha256, and mpvDllSha256 null.
A source tag update changes source-monitor information only. It never switches
the selected installed engine or fills a binary identity from an older MPV DLL.
The real MPV DLL must contain the registered bilipai-rtx filter from the fixed
source manifest; that complete DLL has not yet been built or bound here.

## Own application identity

initialize-veyra-profile.ps1 requires TemplatePath, TrustedTemplateSha256, and an
explicit ProfilePath whose parent already exists. On first use it writes that
file with a new BiliPai GUID. On reuse it requires TrustedExistingProfileSha256
and preserves the existing GUID and profile bytes. It never reads Veyra's local
configuration. The fixed official NVIDIA/DLSS header permits a GUID-like own
identifier with CUSTOM Init_with_ProjectID. Veyra's fixed staging script uses
the same persistent NewGuid pattern. This step does not register an account or
call NGX.

The returned hash is an authoring receipt. The owner must establish the profile
trust anchor independently before enabling verification. A hash read from an
arbitrary selected JSON file is not evidence of trusted build provenance.

## Read-only verification

verify-veyra-runtime.ps1 requires ProfilePath and TrustedProfileSha256. RuntimeRoot
is the selected local package base, defaulting to the profile's parent. The
portable profile sets runtimeRoot to "." and featureDirectory to
"runtime/experimental". CoreModulePath and MpvModulePath explicitly override the
portable defaults "core/bilipai_veyra_core.dll" and "mpv/libmpv-2.dll".

The caller must pin the verifier's exact SHA separately. Every path is local,
has no reparse ancestor, and every opened file is leased for read with no shared
write or delete throughout hashing and signature checks. Profile-relative paths
reject traversal, absolute paths, alternate data streams, and ambiguous names.
The verifier independently pins the actual core and filter source identities,
both exact NVIDIA runtime hashes, their x64 PE identities, versions, and Valid
Authenticode signer subject and certificate thumbprint. It does not read logs,
settings, accounts, or other runtime features.

The helper prints one portable JSON object and exits 0 only for VERIFIED; all
rejections exit 2. Its schema is 1:
- status: VERIFIED or REJECTED
- engineStatus: AVAILABLE or UNAVAILABLE
- failureCode: null or a stable code
- missingInputs: array of required missing binding fields
- checked: profileSha256, moduleBuildSha256, mpvDllSha256, coreSourceSha256,
  headerSha256, coreAbi (1), coreAbiWire (65536), and runtimeFiles
- each checked runtimeFiles item: relativePath, sha256, bytes, fileVersion,
  authenticodeStatus, signerSubject, signerThumbprint

RuntimeOnly is a diagnostics-only mode. It always returns engineStatus
UNAVAILABLE even when both runtime files verify. Application binding must omit
it and supply an independently trusted profile with real core and registered
MPV binary hashes plus the own application GUID. This template therefore
rejects full binding with MISSING_BOUND_MODULE_IDENTITIES.

AVAILABLE means selected material satisfies the identity checks. Native frame
receipts are still required for SUBMITTED; presentation evidence is separate.
The caller must retain its own immutable component leases through Native.load,
all processing, and final unload. The helper's leases end when it exits.

## Evidence and distribution

The observed VSR/TrueHDR files match the user's local Veyra 2.0.6 manifests and
a Valid NVIDIA certificate. No runtime, NVIDIA header, static shim, or DLL is
included in this source directory. The historical actual private core link
hash is evidence only and is not a selected module identity. No built DLL or
GPU processing was executed by this packet. Local private build evidence does
not establish public redistribution compatibility.

The local .gitattributes preserves exact source bytes so the verifier hash
cannot change through line-ending conversion.
