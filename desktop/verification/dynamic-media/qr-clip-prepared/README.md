# Windows printed footer URL clip candidate

This is a Windows AWT drawing adaptation. Actual Windows PNGs produced by the
current Main renderer show the printed footer URL painting into the QR bitmap.
Original Android source provides the same unbounded drawText call, but Android
uses its own default Paint/typeface and rasterization. No Android device or emulator
was run. The prior PNG77 phrase “inherited renderer limitation” describes source
provenance and the Windows result; it does not prove the Android output overlaps.

The narrow candidate adds one dedicated Canvas method and maps one original draw
call to it. The method uses an isolated Graphics2D copy clipped from footerLeft to
the original computed qrLeft and always disposes that copy. It changes only the
printed footer URL's drawable region. It keeps the complete spec.qrUrl argument,
the QR payload, all fields, fonts, coordinates, image dimensions, line cap, colors,
QR size/position, and every ordinary drawText call. Long printed URLs can be
visually clipped at the QR boundary. Their full link remains in the QR payload.

`runs/01` executes two fresh JVMs against real Main snapshot02, manifest
`f164846bb1ebe5545c727824bfa6fe853a5fca92bfd1bcfddf59abb53c943d17`,
ordered 89 CP `be8a62821f74e73653ed2354afbb1de3d6ec8236db68f056328a482a5e0f4d9d`.
Baseline has zero production overrides: 20 assertions / 8 declared synthetic routes,
5 decode failures and 8 non-pristine QR images. Candidate has 37 assertions /
8 routes, zero decode failures and zero QR pixel differences from the original
QRCodeWriter matrices, including all quiet-zone pixels. Payloads include long
video/root IDs, Long.MAX_VALUE and original secondary-comment routes. The IDs are
self-declared input fields, not fetched accounts or network material.

The candidate explicitly overrides only the DesktopCommentCanvas and original
renderer class families. Real Main spec builder, Paint, bitmap factory, writer,
ImageIO and Files remain unmodified and their codeSource is checked. The task
compiler extracts only the Canvas class from the full install candidate, so the
unchanged writer top-level class is not overridden. This is prepared integration,
not actual installed Main acceptance.

Independent Pillow comparisons confirm identical baseline/candidate specs and
geometry. Every changed pixel lies in the printed footer URL row at or beyond
qrLeft. Candidate saved QR crops decode to the exact full original links and are
pixel-for-pixel equal to the original matrix. A separate draw after the clipping
call verifies ordinary Graphics state is restored. Visual inspection of the long
secondary route confirms the QR is clear and only the printed URL is clipped.

Original Saver bytes are equal to the pinned v0.2.3-alpha.9 Git source. Fresh
baseline producer bytes equal the real Main generated renderer. Of all 16 fresh
producer outputs, only the renderer changes, and that output differs by exactly
the one call mapping listed in source-audit.json. Neither renderer business
decisions nor QR generation are replaced. Source pin observer prepare01 failed
before output because it expected the wrong generated manifest field; that
failure is retained and the corrected observer uses raw generated-byte pins.

There is no HWND, chooser, browser, HTTP, system share, receiver, Android render,
or Main/Gradle edit. PNG77 and Gallery233/Assets114 frozen bytes are untouched.
Use ROOT-INTEGRATION.txt and the exact diffs for Root review before installation.

Independent read-only review is copied byte-for-byte in
`independent-readonly-review.json`, SHA
`b449fc035e32700dcb3b461a9805cbe679029625456dbc3a92d860f25cff8a17`.
It found no additional blocker in this declared Windows clipping slice, verified
source/diff/receipt pins and scope, and did not rerun runtime tests or any window.
