#!/usr/bin/env python3
"""Retained navigation parsers, message-link routing and JVM link opening."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, json

BASE = 'app/src/main/java/com/android/purebilibili/'
SOURCES = {
    BASE+'core/util/BilibiliNavigationTargetParser.kt': 'direct',
    BASE+'core/util/BilibiliUrlParser.kt': 'platform-rewrite',
    BASE+'core/util/UrlDecodeCompat.kt': 'direct',
}

def inventory(repo):
    return [dict(path=p,mode=m,features=['space'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_bytes().replace(b'\r\n',b'\n')).hexdigest()) for p,m in SOURCES.items()]

def generate(repo, output, policy_only=False, tests_output=None):
    import v043_navigation as navigation
    import v043_message_link_host as message_host
    import v043_link_open as link_open
    navigation.validate_outputs(repo, output, tests_output)
    def read(suffix): return (_desktop_canonical_source(repo, Path(BASE) / suffix)).read_text(encoding='utf-8')
    originals, proof = navigation.original_sources(repo,
        read('core/util/BilibiliNavigationTargetParser.kt').encode('utf-8'),
        read('core/util/BilibiliUrlParser.kt').encode('utf-8'))
    for path, raw in originals.items():
        target=output/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    (output/'v043-navigation-proof.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8',newline='\n')
    message_sources, message_proof = message_host.original_policy(repo,
        read('navigation/MessageLinkNavigationPolicy.kt').encode('utf-8'))
    for path, raw in message_sources.items():
        target=output/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    (output/'v043-message-link-host-proof.json').write_text(json.dumps(message_proof,indent=2)+'\n',encoding='utf-8',newline='\n')
    for path, raw in link_open.original_sources(repo).items():
        target=output/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    if tests_output is not None:
        for path, raw in {**navigation.original_tests(repo), **message_host.original_tests(repo), **link_open.original_tests(repo)}.items():
            target=tests_output/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--test-output',type=Path);cli.add_argument('--inventory',action='store_true');cli.add_argument('--policy-only',action='store_true')
    args=cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo),indent=2))
    else:
        if args.output is None:cli.error('--output is required')
        generate(args.repo,args.output,args.policy_only,args.test_output)
