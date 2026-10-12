# BiliPai Windows coordination

The user has appointed the main task as integration controller (Root). Work in
an independent linked worktree. Do not modify another agent's checkout or shared
Git index. Root owns the integration branch, version metadata, build files,
upstream tracking, final merges, packaging and the single desktop installation.

## Accepted baseline

Read the shared registry before changing source. Its `acceptedHead` is the
accepted Windows integration commit, not the upstream Android version. The full
source baseline is v0.2.5 / 79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40;
v0.3.4 / a69d4181a073aa3207def3fca43318dcf7ab2c70 is reused in explicit slices.
Neither a branch name nor old README sections prove complete version parity.
The old `work/BiliPai` alpha.9 checkout is not a development baseline.

## Shared registration

For this local task the state directory is:
`C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/.coordination`.
Use the controller's current tool:
`work/BiliPai-v025/desktop/tools/worktree-module-coordinator.py`.

Root initializes and configures the registry. Before editing, register your clean
linked worktree with its exact base SHA, module, optional chat ID, and explicit
`file:repo/path` or `prefix:repo/directory` scopes. Review-only modules may use
`--read-only`. Registration rejects old baselines, Root's checkout and conflicting
write scopes. If an existing task already has edits or its scope must change,
preserve those edits and submit the paths and commit/base to Root; do not reset,
rebase, release another claim, or rewrite the registry to obtain admission.
Root may use `register --adopt-committed` to preserve an existing clean descendant
commit after checking its entire diff against explicitly approved scopes.

Run `check --record-id UUID` before delivering. Commit only your own changes and
run `deliver --record-id UUID`; send Root the exact commit and validation report.
Delivery checks the real diff, including untracked paths, requires a clean
worktree, and retains the claim. Root reviews, integrates and explicitly releases
it after the participant stops work. An expired record still reserves its scope.
Unknown or malformed state is preserved for Root to inspect.

The tool is a cooperating-agent contract, not a filesystem sandbox or an agent
liveness detector. File timestamps and `ACTIVE` do not prove a chat is running.
Do not claim coordination until the actual worktree and scope are registered.

## Development and verification

Develop the bottom layer first, then run the smallest meaningful verification.
Do not generate or edit build outputs as source. Use the real connected desktop
implementation and retained upstream code; do not implement a parallel imitation
to pass tests. Report source/compile/test/package/UI/hardware evidence separately.
Root serializes shared Gradle verification and final integration.

Keep changes targeted and avoid unsolicited dependencies. Do not start the app,
activate windows, simulate input, play media, change GPU/audio configuration,
touch account data, or disturb the user's game without current authorization.
Do not replace or duplicate desktop builds. Only Root pushes accepted changes to
tonysuper666-creator/BiliPai-Windows. Do not contact upstream authors or trigger
paid cloud builds. Public standard GitHub runners are the allowed cloud builds.
Do not add AI co-author or generated-by attribution to commits.
