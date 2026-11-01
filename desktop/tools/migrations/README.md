# Reviewed video migration batches

Run from the repository root, with Python 3 and the local Git history/tags:

```sh
python3 desktop/tools/migrate-video-batch.py desktop/tools/migrations/ton-1002-a-event-state.json --dry-run
python3 desktop/tools/migrate-video-batch.py desktop/tools/migrations/ton-1002-a-event-state.json --apply
python3 desktop/tools/migrate-video-batch.py desktop/tools/migrations/ton-1002-a-event-state.json --apply
python3 -m unittest discover -s desktop/tools/tests -p test_migrate_video_batch.py -v
```

The first command lists every planned file and its resulting digest. The second
writes a counted unified patch and exact source/producer registrations. The third
must report `changedFiles: 0`. Upstream Kotlin stays untouched. No network access,
Gradle, client startup, credentials, or runtime data is involved in these commands.

A recipe pins a local upstream tag/commit and every source's SHA-256 after CRLF
normalization. The tag must be an ancestor of the commit, the commit must already
be merged into HEAD, and current source bytes must match that revision. Each edit
must match its declared count (default one). Patches are deterministic and passed
to the existing `applyDesktopUpstreamPatches` build task; this tool does not add a
second compilation mechanism. Registration changes preserve unrelated entries.

The tool rejects traversal, symlinks, sources outside video/bangumi, duplicate
identities, changed upstream bytes, missing edit anchors, and a pre-existing patch
that differs. It checks the whole batch before writing and rechecks all targets
before the first write. A conflicting adaptation needs manual composition and a
new reviewed recipe; never refresh a hash just to bypass a conflict. Run in an
exclusive worktree. An interrupted OS process can leave a partial batch; inspect
Git before rerunning (completed files are idempotent). Ordinary write failures
roll back already written files.

For the next batch, copy the small recipe and review the new source revision,
hashes, edits, exact generated identities and `pending` list. Keep a batch limited
to a single dependency closure. This is a mechanical codemod, not dependency
discovery: dry-run success does not prove compilation or user-visible behavior.
Existing patches with additional desktop behavior require explicit composition.
Run the same-environment JVM baseline comparison and `verifyUniqueClassNames`
before accepting any batch that enters Kotlin compilation.

## A1 event-state boundary

`ton-1002-a-event-state.json` migrates only
`VideoDetailPlaybackSessionEffects.kt` and retires its old generated identity.
It uses the existing retained Root danmaku/settings/analytics ports. Upstream
media-key filtering and screen-owned resume state enter compilation.

Three exceptions remain explicit:

- Until the dedicated resume feedback component, holder provider and player section
  migrate together, resume messages continue to use the existing popup. Remove
  that transitional block only in the host batch, so a message cannot disappear.
- The existing local danmaku port has four parameters. VIP gradual color remains
  unchanged; this batch does not add support or claim it.
- The playback VM still comes from its legacy producer. Migrating the event-state
  consumer does not deliver the new upstream resume-notice emission path.

Aspect-ratio work (TON-1056) and BGM detail (TON-1004) are outside this batch.
