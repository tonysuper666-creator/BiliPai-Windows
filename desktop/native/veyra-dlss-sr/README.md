# Standard DLSS SR through the shared native core

This directory preserves the fixed 15-file standard DLSS SR upstream closure
and the real v2 adapter. Its CMake entry delegates to the neighboring veyra-core
target. The resulting bilipai_veyra_core DLL has both v1 and v2 entrypoints and
one shared process NGX host. No independent DLSS host or DLL is constructed.

The v2 wire layout remains config=112, frame=168, result=120, status=288 bytes.
Its public header defines real linear BT.709/scRGB RGBA16F, target-size R32
depth and target-size R16G16 motion textures, genuine same-device queue/fence
leases, reset/history identity and output completion. The caller adapter version
remains BiliPai-Veyra-DLSS-SR-2; actual NGX initialization uses the fixed shared
host version BiliPai-Veyra-Core-Shared-1 with the same own persisted GUID/root.

Both actual adapters consume the same mutex and shared host lease. Another
active/quarantined ABI owner makes create return BUSY. Failure or throwing SDK
retirement permanently retains the entire owner and completed-frame COM leases.
Only real same-device Shutdown1 Success/no-SEH permits transferring ownership.
The module and bounded original runtime slots remain pinned for process life.
See the neighboring README and shared-host manifests for full build/lifetime
closure; old independent-target build identities are superseded.

CMake enforces the 18+15 raw fixed files, 20-file unique union, eight owned
sources and all six actual fixed official SDK headers. Release /MT link mode
also verifies the official static shim. There are no NVIDIA dependencies or
binaries in the source payload. BILIDLSS_COMPILE_ONLY defaults to ON and drives
the shared target's matching option; linking needs explicit external paths.

Runtime authentication, actual linear/depth/motion production and player
consumer/timeline integration remain pending. No app path or capability switch
is attached. NR, DLSS Frame Generation and NvVFX VFG remain unintegrated. CPU
compile/link receipts do not establish SDK execution, frame quality or display.
