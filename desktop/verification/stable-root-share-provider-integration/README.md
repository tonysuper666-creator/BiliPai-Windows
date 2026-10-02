# Provide the existing image-share binding before the Root comment owner

The first actual Search integration window on immutable product86 failed before publishing navigation: the standalone Root comment wrapper read LocalDesktopImagePreviewShareBindings outside the later Home window provider. No Search UI assertion passed in that run. The failure was found by running actual DesktopApp and RootStack, with no production class overlay, in a fresh isolated environment.

One exact Shell hunk now provides environment.gallery.imageShare around the existing comment wrapper. The gallery is already owned by the same retained embedded aggregate; this borrows its existing binding, file pool and native actor. It creates no resource, network client or preference store. Comment/video ownership, account epoch, caller Job guards and the existing retirement order remain unchanged. The hunk was independently verified against its Search baseline and applied with an exact inverse on the newer Bangumi revision.

Normal main and test-source compilation passed with no product override. The preserved actual86 failure and input pins explain why compilation alone did not accept the Root window. A fresh immutable product snapshot and actual Window retest are still required; this source commit does not yet accept Search or ordinary account playback. The user's deployed EXE is unchanged.
