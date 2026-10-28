# BiliPai Windows coordination

The user has appointed the main task as integration controller (Root). Work in
an independent linked worktree. Do not modify another agent's checkout or shared
Git index. Root owns the integration branch, version metadata, build files,
upstream tracking, final merges, packaging and the single desktop installation.

## Accepted baseline

The accepted baseline is the current HEAD of `desktop/integration-263-claude-main`,
the accepted Windows integration commit, not the upstream Android version. The full
source baseline is v0.2.5 / 79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40;
v0.3.4 / a69d4181a073aa3207def3fca43318dcf7ab2c70 is reused in explicit slices.
Neither a branch name nor old README sections prove complete version parity.
The old `work/BiliPai` alpha.9 checkout is not a development baseline.

## Card registration

Coordination happens on the Multica issue, not in a local state directory. The card
records a `write_scope` metadata value naming its base SHA, its branch, and its
explicit `file:repo/path` or `prefix:repo/directory` write scopes. Before editing,
read the `write_scope` of the other open cards and the open pull requests in the same
project; if a range overlaps yours, write that on your card and hand the overlap to
Mika to sequence.

Each card works in its own `multica repo checkout` worktree. Do not modify another
agent's checkout and do not touch the shared Git index.

Commit only your own changes and open the pull request against
`desktop/integration-263-claude-main`, unless the card names another base. Build
files, version metadata, upstream tracking, final merges and packaging stay with the
integration controller.

`write_scope` is a cooperating-agent contract, not a filesystem sandbox or an agent
liveness detector. An empty overlap check does not prove another chat is idle.

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
