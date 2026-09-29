# Native player distribution

BiliPai Windows includes an unmodified Windows x64 libmpv build produced by
shinchiro/mpv-winbuild-cmake on 2026-09-03. The archive and DLL are verified by
SHA-256 before packaging. This dynamically loaded DLL can be replaced separately
from the application.

mpv is copyright its contributors. Its GPL and LGPL license texts and detailed
copyright statement are included in `licenses/`. FFmpeg copyright and applicable
license texts are included alongside them. These are upstream files copied from
the exact mpv and FFmpeg commits identified by the binary producer.

`SOURCES.json` records the full mpv and FFmpeg source commits, source archives,
original binary URL, build recipe commit, build workflow, dependency recipe
directory, and the origin and checksum of every included license file. The build
recipes identify the additional linked libraries and their source repositories.
The original build instructions are available at the pinned build recipe commit.

The native archive and source references are retained on this project's runtime
release so daily application builds do not depend on retention of a vendor's
nightly releases. Application source and its GPLv3 license accompany each Windows
release through its fixed source commit and source archive.
