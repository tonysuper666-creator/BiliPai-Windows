# Final cohort source identity count

The current snapshot contains **30 pinned file identities**: 16 source files and
14 generated files. Its added source identity is
`desktop/tools/tests/test_diagnostic_sources.py`. All 30 were actually verified;
`source-seam-verification.json` records the correct dynamic count.

The first final-cohort freeze inherited the historical count 29 in the report and
metadata builder. Preserve those frozen bytes at SHA
`740503984502bd4f0d3ac88a714d79de378d357eca9fba6ce2ddf48c34b41e17`;
the final `frozen-handoff.json` corrects only the count to 30. Historical lifecycle
cohort correctly remains 29. No product or executable fixture changed, and all
seven scenarios/70 assertions/15 contracts/six PNGs remain the same evidence.

For a future metadata builder, derive sourceIdentityCount from
sourceSeams['sourceAndGeneratedIdentityCount'] instead of the inherited literal.
