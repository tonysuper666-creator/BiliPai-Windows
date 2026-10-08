# Manual native candidate producer

This build is opt-in and source-only until the manual workflow actually completes.
It fixes the mpv and FFmpeg commits, native patch, full recipe archive and the exact
historical container digest. Other dependency recipes still select branches/tags or
unfixed source revisions. Actual dependency commits, tools and source worktrees are
recorded; this does not establish reproducibility or NVIDIA 1x processing support.

The workflow has workflow_dispatch only, an explicit false-by-default acknowledgement,
standard ubuntu-24.04 hosted runner and contents:read. It uploads candidate/failure
artifacts; it does not publish a release or enable another workflow. The cold targets
llvm/rustup/llvm-clang/mpv and mpv-dev DLL output are real cd1 targets, not new invented
vendor commands. A cold toolchain may exceed the free runner time/disk limits. That is
a genuine pending build limitation; no completed build or binary hash is supplied here.

The producer emits runtime-descriptor.json only after an actual DLL, source receipt,
PE AMD64 imports, source bundle and fixed license bytes validate. Unknown non-system
companion imports fail instead of silently extending the portable loader or obtaining
SDKs. Two existing optional VapourSynth delay imports are recorded, not bundled or
claimed available. No GPU/device/driver test or 1x Active state is part of this job.

Download the complete uploaded artifact, retaining its actual source-materials bundle.
Use the existing fetch-mpv.ps1 with -RuntimeDescriptorPath and optional
-RuntimeArchivePath, or existing build.ps1 with -MpvRuntimeDescriptorPath and
-MpvRuntimeArchivePath. Omitted descriptor keeps the original portable path unchanged.
Missing/invalid descriptors, old archive/DLL identities, extra ZIP entries, wrong
sources/receipt/licenses or hashes fail closed. There is no original-DLL fallback.

For remote delivery, after inspecting the real build, Root may publish the generated
ZIP plus source-materials bundle to the existing owned release repository and bind
those actual asset URLs in the real descriptor. They must be the named patch variant
assets under tonysuper666-creator/BiliPai-Windows/releases/download. URLs are never
invented here. Changing URLs changes descriptor identity and requires cache validation
again. Its archive/DLL/source hashes are measured outputs, not hand-filled placeholders.

The source bundle records complete active source worktrees (without Git objects),
fixed full archives, modified recipes and ownrepo helper/patch inputs. It is meaningful
source evidence, not an independently established legal/completeness/reproducibility
certification. Publish only after reviewing actual dependency licenses and build scope.

The staged provenance fields variant/sourceCommit/nativePatchSha256/dllSha256 bind
the actual selected binary to this source candidate. They never prove a PPE effect.
Runtime 1x admission must additionally hash the DLL at its actual loaded path; the
original default runtime has no custom variant and must not enter the new 1x attempt.
