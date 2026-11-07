#!/usr/bin/env python3
"""Emit the retained original Space playback policy and its original tests."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, json

BASE = 'app/src/main/java/com/android/purebilibili/'
SOURCES = {BASE+'feature/space/SpacePlaybackPolicy.kt': 'direct'}

def inventory(repo):
    return [dict(path=p, mode=m, features=['space-overview'],
                 sha256=hashlib.sha256(_desktop_canonical_source(repo, p).read_bytes().replace(b'\r\n', b'\n')).hexdigest())
            for p, m in SOURCES.items()]

def generate(repo, output, policy_only=False, test_output=None):
    from v044_favorite_detail import validate_outputs, emit_policy
    validate_outputs(repo, output, test_output)
    predecessor = _desktop_canonical_source(repo, BASE+'feature/space/SpacePlaybackPolicy.kt').read_text(encoding='utf-8')
    emit_policy(repo, output, test_output, predecessor)

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--inventory',action='store_true');cli.add_argument('--policy-only',action='store_true');cli.add_argument('--test-output',type=Path)
    args=cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo),indent=2))
    elif args.output: generate(args.repo,args.output,args.policy_only,args.test_output)
    else: cli.error('--output is required')
