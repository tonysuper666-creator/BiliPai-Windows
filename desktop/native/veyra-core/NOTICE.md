# Source and dependency notice

The selected Veyra code is from:
- Repository: https://github.com/Likely7/Veyra-NRVideo
- Frozen source: 96a7c8de36bc195240161de6814739ad810722f1 (2.0.6)
- Official v2.0.6 release commit: e3aa842a00f693a4a17d1fc4c454d0ebc59428f6
- All 18 selected source/header/license git blobs are identical between the
  frozen source and that release commit. This is a minimal selection of real
  NgxCoreHost, VideoSrBackend, TrueHdrBackend and their logging/type dependencies.
- License: GNU GPL version3; the upstream license is preserved verbatim as
  `upstream/LICENSE`. Original source notices and bytes are preserved.

The BiliPai ABI adapter is application-owned source. Its exact reviewed source
and ABI bytes are retained here. Only CMake's default fixed-source directory
was changed to the repository-local `upstream/` directory for this packet.

NVIDIA/DLSS SDK dependency is external and independently licensed. The reviewed
SDK commit is 374959484e79a640feaba44c93ac8cfb0a03f5b5. The actual header and
Release x64 static-shim identities are recorded in `SDK_PROVENANCE.json`.
No NVIDIA source/header/license file, static library, runtime or model bytes
are copied into this repository source packet. Retrieve/use any required SDK
through its own authorized process and preserve its separate license records.
Compilation does not determine SDK/GPL redistribution compatibility or authorize
public distribution of a combined binary.
