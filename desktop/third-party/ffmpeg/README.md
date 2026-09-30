# FFmpeg for Windows download merging

BiliPai uses the separate `ffmpeg.exe` process for local MP4/M4A copy merging,
and `ffprobe.exe` for the native verification gate. FFmpeg is not linked into
BiliPai. The pinned BtbN Windows x64 LGPL build is
`n8.1.2-50-g1a748fe2cd-20260831`, retained in the monthly release
`autobuild-2026-08-31-13-27`. Its actual configuration includes
`--enable-version3` and excludes `--enable-gpl` and `--enable-nonfree`.

Run `powershell -File desktop/tools/fetch-ffmpeg.ps1` to download and verify
the fixed runtime and source archives. `-Offline` requires all three cached
archives and makes no network requests. Both executable hashes are pinned
independently of the archive. The script verifies every inventoried license
file, copies the notices to `native/windows-x64/licenses/ffmpeg`, copies exact
FFmpeg and build recipe archives to `native/windows-x64/sources/ffmpeg`, and
writes `ffmpeg-provenance.json`.

The bundled FFmpeg license notice and LGPL/GPL license texts are copied from
the exact FFmpeg source revision. The GPLv3 text accompanies the LGPLv3 text
because LGPLv3 incorporates it; this does not enable FFmpeg GPL-only code.
The build recipe license is copied from the fixed BtbN recipe revision.
`SOURCES.json` records immutable source URLs, hashes, all dependency recipe
source parameters, and the observed binary configuration. Its dependency
license inventory states the collected notice coverage explicitly.

To inspect or rebuild the executable, extract the supplied FFmpeg source and
BtbN recipe archives. The recipe target is `win64 lgpl 8.1`; run
`./makeimage.sh win64 lgpl 8.1` and `./build.sh win64 lgpl 8.1` in the builder's
Docker environment. Before configure, replace the builder's default branch
checkout with the exact FFmpeg source commit from `SOURCES.json`. The default
builder follows the 8.1 branch, so using the branch alone is not the pinned
source. These source and recipe records do not promise byte-identical builds.

FFmpeg upstream: <https://ffmpeg.org/>,
<https://github.com/FFmpeg/FFmpeg>.
Binary and build recipe supplier: <https://github.com/BtbN/FFmpeg-Builds>.
The original authors retain their copyrights; license texts and individual
source URLs are in the adjoining license directory and catalog.
