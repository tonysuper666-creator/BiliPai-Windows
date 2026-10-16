#!/usr/bin/env python3
"""Sole producer of the v0.4.1 video status label models, VideoStatusBadges and original tests.

Emits VideoStatusLabels, VideoStatusBadges, ListModels, SpaceModels, SearchModels (hooks SM1/SM2 and the
complete v043 UP-author follow-up) and
FavoriteModels (hook FM1) under --output, and the two original status-label tests with their
test-only durationSeconds/asVideoItem support plus both original UP-search tests under --tests-output. Every pin, exact-once anchor and
inverse replay (including the consumer hooks applied by the card/screen generators) is verified
before any file is written; the proof receipt lands beside the main sources.
"""
import argparse
import json
from pathlib import Path
from v041_video_status_labels import generate

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tests-output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.repo.resolve(), args.output.resolve(), args.tests_output.resolve()), sort_keys=True))
