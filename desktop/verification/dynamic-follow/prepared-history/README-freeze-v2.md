# Complete raw freeze correction

Use `frozen-handoff-v2.json` as the final handoff manifest. The first `frozen-handoff.json` remains unchanged at SHA `7b7ad3fdc43fbc18ece03644be567f41625929cb8350556ba5b54f7ac2d45654` as historical freeze metadata.

Independent extended-path enumeration found that Windows normal `Path.is_file()` omitted 12 existing long-path raw Kotlin files from the first manifest, including two retained original Repository adapters and some frozen run-source copies. None of the source, binary, assertion, review, or accepted-run bytes changed. The v2 manifest enumerates and hashes every permitted raw file using the Windows extended path API, verifies every entry, and includes the first manifest and this correction as raw history. Final prepared acceptance remains runs/08, 92 assertions/18 cases, exact source contracts 32, and the unchanged independent review. Root must use v2 when copying raw evidence into formal verification.
