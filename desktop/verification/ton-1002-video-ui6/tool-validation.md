### dry-run
Command: python3 <worktree>/desktop/tools/migrate-video-batch.py <worktree>/desktop/tools/migrations/ton-1002-video-ui6.json --dry-run
Exit: 0
```
{
  "batch": "ton-1002-video-ui6-independent-ui",
  "mode": "dry-run",
  "upstream": {
    "tag": "v0.5.0",
    "commit": "383be1d47ff1b46c8c793e4c78e28bcbf715b331"
  },
  "sources": [
    "com/android/purebilibili/feature/video/ui/components/GlassComponents.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LandscapeUpInfo.kt"
  ],
  "changes": [
    {
      "path": "desktop/build.gradle.kts",
      "action": "update",
      "sha256": "95cffb510246a719122e717c981e8922a756b880d59f7a7b83a5a5eda4f669a5"
    }
  ],
  "pending": [
    "Export-only: neither source has an existing desktop consumer or generated copy at the pinned head; no generated copy is retired.",
    "Root must separately authorize registration and verify desktop compilation, unique class names, and actual consumer use.",
    "A2 real animation remains unverified; no claim of increased production migration."
  ],
  "changedFiles": 1
}
```

### first export
Command: python3 <worktree>/desktop/tools/migrate-video-batch.py <worktree>/desktop/tools/migrations/ton-1002-video-ui6.json --export-patch <worktree>/desktop/verification/ton-1002-video-ui6/export.patch
Exit: 0
```
{
  "batch": "ton-1002-video-ui6-independent-ui",
  "mode": "dry-run",
  "upstream": {
    "tag": "v0.5.0",
    "commit": "383be1d47ff1b46c8c793e4c78e28bcbf715b331"
  },
  "sources": [
    "com/android/purebilibili/feature/video/ui/components/GlassComponents.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LandscapeUpInfo.kt"
  ],
  "changes": [
    {
      "path": "desktop/build.gradle.kts",
      "action": "update",
      "sha256": "95cffb510246a719122e717c981e8922a756b880d59f7a7b83a5a5eda4f669a5"
    }
  ],
  "pending": [
    "Export-only: neither source has an existing desktop consumer or generated copy at the pinned head; no generated copy is retired.",
    "Root must separately authorize registration and verify desktop compilation, unique class names, and actual consumer use.",
    "A2 real animation remains unverified; no claim of increased production migration."
  ],
  "changedFiles": 1,
  "exportedPatch": {
    "path": "<worktree>/desktop/verification/ton-1002-video-ui6/export.patch",
    "sha256": "ff70daf00cf3c669f4f2ac5dba32988cbda0f2170272f735e6ba0d8ea498a554"
  }
}
```

### second export
Command: python3 <worktree>/desktop/tools/migrate-video-batch.py <worktree>/desktop/tools/migrations/ton-1002-video-ui6.json --export-patch <workspace>/video-ui6-second.patch
Exit: 0
```
{
  "batch": "ton-1002-video-ui6-independent-ui",
  "mode": "dry-run",
  "upstream": {
    "tag": "v0.5.0",
    "commit": "383be1d47ff1b46c8c793e4c78e28bcbf715b331"
  },
  "sources": [
    "com/android/purebilibili/feature/video/ui/components/GlassComponents.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LandscapeUpInfo.kt"
  ],
  "changes": [
    {
      "path": "desktop/build.gradle.kts",
      "action": "update",
      "sha256": "95cffb510246a719122e717c981e8922a756b880d59f7a7b83a5a5eda4f669a5"
    }
  ],
  "pending": [
    "Export-only: neither source has an existing desktop consumer or generated copy at the pinned head; no generated copy is retired.",
    "Root must separately authorize registration and verify desktop compilation, unique class names, and actual consumer use.",
    "A2 real animation remains unverified; no claim of increased production migration."
  ],
  "changedFiles": 1,
  "exportedPatch": {
    "path": "<workspace>/video-ui6-second.patch",
    "sha256": "ff70daf00cf3c669f4f2ac5dba32988cbda0f2170272f735e6ba0d8ea498a554"
  }
}
```

Two exports byte-identical: sha256 ff70daf00cf3c669f4f2ac5dba32988cbda0f2170272f735e6ba0d8ea498a554

### fixture apply
Command: git apply --check <worktree>/desktop/verification/ton-1002-video-ui6/export.patch
Exit: 0
```
```

### fixture apply
Command: git apply <worktree>/desktop/verification/ton-1002-video-ui6/export.patch
Exit: 0
```
```

### fixture repeated dry-run
Command: python3 <worktree>/desktop/tools/migrate-video-batch.py <worktree>/desktop/tools/migrations/ton-1002-video-ui6.json --repo <fixture> --dry-run
Exit: 0
```
{
  "batch": "ton-1002-video-ui6-independent-ui",
  "mode": "dry-run",
  "upstream": {
    "tag": "v0.5.0",
    "commit": "383be1d47ff1b46c8c793e4c78e28bcbf715b331"
  },
  "sources": [
    "com/android/purebilibili/feature/video/ui/components/GlassComponents.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LandscapeUpInfo.kt"
  ],
  "changes": [
    {
      "path": "desktop/build.gradle.kts",
      "action": "unchanged",
      "sha256": "95cffb510246a719122e717c981e8922a756b880d59f7a7b83a5a5eda4f669a5"
    }
  ],
  "pending": [
    "Export-only: neither source has an existing desktop consumer or generated copy at the pinned head; no generated copy is retired.",
    "Root must separately authorize registration and verify desktop compilation, unique class names, and actual consumer use.",
    "A2 real animation remains unverified; no claim of increased production migration."
  ],
  "changedFiles": 0
}
```

### bad-fingerprint
Command: python3 <worktree>/desktop/tools/migrate-video-batch.py <workspace>/video-ui6-bad-fingerprint.json --repo <fixture> --apply
Exit: 2
```
{"error": "Upstream version/source changed: app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/LandscapeUpInfo.kt", "status": "failed"}
```

bad-fingerprint: fixture git diff unchanged; no partial writes.

### path-traversal
Command: python3 <worktree>/desktop/tools/migrate-video-batch.py <workspace>/video-ui6-path-traversal.json --repo <fixture> --apply
Exit: 2
```
{"error": "Unsafe path: app/src/main/java/com/android/purebilibili/feature/video/../../../../../../../../outside.kt", "status": "failed"}
```

path-traversal: fixture git diff unchanged; no partial writes.

### existing tool tests
Command: python3 -m unittest discover -s desktop/tools/tests -p test_migrate_video_batch.py
Exit: 0
```
...<stdin>:8: trailing whitespace.
[whitespace-only line]
warning: 1 line adds whitespace errors.
<stdin>:8: trailing whitespace.
[whitespace-only line]
warning: 1 line adds whitespace errors.
..
----------------------------------------------------------------------
Ran 5 tests in 1.195s

OK
```

### Atomicity with untracked files
bad-fingerprint: repeat --apply exit 2; git status including untracked files unchanged.
path-traversal: repeat --apply exit 2; git status including untracked files unchanged.
