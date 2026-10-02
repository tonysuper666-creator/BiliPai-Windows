# Locally deployed Windows test package

The v0.2.3 test package, Windows version `0.2.415.1`, was built from commit `6fd5bbd804f272650942f5a445385ba5428ffa9b` and deployed to `C:/Users/TONYS/Desktop/BiliPai Windows v0.2.3/BiliPai Windows.exe`. All 1122 payload files matched the ZIP at deployment. The package SHA-256 is `8aac45f341dd04cab4700dde9b537bcc7388d0df334d9be6562f1136f5725cf9`.

The actual packaged player passed 29 offline checks; download muxing decoded 6 output variants. The packaged updater tested real EXE activation, hash/version mismatch rejection and startup failure fallback using an isolated loopback release feed. A separate run supplied the real previous `0.2.406.5` package and confirmed its original EXE forwards to the healthy new version. Physical Windows shortcuts and a public Windows release feed were not tested.

The unchanged actual product's authorized ExternalMedia branch passed 17 Root checks, including source changes, native first-frame state, PiP/restore and shutdown. These are semantic callbacks through the actual plugin/runtime/native player; decoded pixel visibility, physical pointer controls and Settings occlusion are not established. The separate ordinary guest Bilibili attempt observed HTTP 412 and did not reach video detail Success or native first frame. No account Cookie was copied. Real account playback remains pending user testing.

This checkpoint does not establish complete feature parity, v0.2.4 alignment, public release publication or enabled automatic upstream synchronization. Later source edits do not retroactively become part of this deployed package. Existing desktop directories, user account data and existing processes were preserved.

`artifact-index.json` indexes the selected raw copies. Every original local manifest entry was hash-verified before copying. `checkpoint.json` explicitly lists package artifacts retained only in the local lane, including the large before/after source inventory and full file manifests. Other lanes' raw manifests are copied in full except their already declared compiled fixture binaries. The original paths remain historical data; no runtime JAR, ZIP or DLL is duplicated here.
