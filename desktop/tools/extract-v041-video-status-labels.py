#!/usr/bin/env python3
"""Sole producer of the v0.4.1 video status label models, VideoStatusBadges and original tests.

Emits VideoStatusLabels, VideoStatusBadges, ListModels, SpaceModels, SearchModels (hooks SM1/SM2 and the
complete v043 UP-author follow-up) and
FavoriteModels (hook FM1), plus the whole v043 native Home ListModels/RecommendationReason
follow-up under --output. The original status-label tests, test-only durationSeconds/asVideoItem
support, UP-search tests and twenty original Home model/navigation cases go under --tests-output. Every pin, exact-once anchor and
inverse replay (including the consumer hooks applied by the card/screen generators) is verified
before any file is written; the proof receipt lands beside the main sources. It also emits the three
v0.4.6 data.model policies and their original test via v046_core_data_policies.
"""
import argparse
import json
from pathlib import Path
from v041_video_status_labels import generate
import v046_core_data_policies as policies

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tests-output', type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = args.output.resolve()
    tests_output = args.tests_output.resolve()
    policies.validate_outputs(repo, output, tests_output)
    summary = generate(repo, output, tests_output)
    policies.emit(repo, output, tests_output)
    print(json.dumps(summary, sort_keys=True))
