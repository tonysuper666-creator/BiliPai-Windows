# BiliPai Windows coordination

The user has appointed the main task as integration controller (Root). Work in
an independent linked worktree. Do not modify another agent's checkout or shared
Git index. Root owns the integration branch, version metadata, build files,
upstream tracking, packaging and the single desktop installation.
Pull requests into the integration branch that have passed their card's acceptance review, are MERGEABLE, and pass the targeted local gate may be merged by the reconciling or reviewing agent (decision by the user, 2026-10-31). Root still owns upstream merges, version metadata, build files and packaging.
When the main session is not active, Architect acts as Root for the items on the Root list of the coordination card (decision by the user, 2026-11-05: the project should run autonomously). This covers gate A runs on a fixed integration SHA, integrating conflicting pull requests, version metadata, build files and packaging. Before Architect pushes to the integration branch, an agent from a different vendor (Expert by default) must review the diff and the gate A evidence. Changing `desktop/tools/gate-a-baseline.json` additionally needs a written reason on the card.

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
files, version metadata, upstream tracking and packaging stay with the
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
Inside the Parallels VM `BiliPai-Win11` the user has given that authorization (2026-11-05): agents start the app, activate windows, simulate input, take screenshots and reuse the logged-in test profile there themselves instead of asking the user. This never applies to `winbox` or the user's own machines.
Do not replace or duplicate desktop builds. Only Root pushes directly to the integration branch; accepted pull requests are merged as described above.
Do not contact upstream authors or trigger
paid cloud builds. Public standard GitHub runners are the allowed cloud builds.
Do not add AI co-author or generated-by attribution to commits.
