"""Desktop build patch; only call on a generated/staged copy, never an upstream source.

M0 can reuse patch() on its staged core-data PublicContentNetwork.kt before compilation.
The original test and archive remain untouched. Fail closed if upstream changes the anchor.
"""
import hashlib
import json
from pathlib import Path
import sys

BEFORE = '    url.queryParameterNames.filter(::isPublicContentCredential).forEach(builder::removeAllQueryParameters)'
AFTER = '    com.bilipai.desktop.network.removeDesktopCredentialQueryParameters(builder, url, ::isPublicContentCredential)'


def patch(source):
    if source.count(BEFORE) != 1 or AFTER in source:
        raise ValueError('Public content credential patch anchor changed; review upstream before building')
    return source.replace(BEFORE, AFTER, 1)


if __name__ == '__main__':
    root = Path(sys.argv[1])
    target = root / 'com/android/purebilibili/core/plugin/feed/PublicContentNetwork.kt'
    before = target.read_text(encoding='utf-8')
    after = patch(before)
    target.write_text(after, encoding='utf-8', newline='\n')
    receipt = root / 'v046-rss-public-content-proof.json'
    proof = json.loads(receipt.read_text(encoding='utf-8'))
    proof['network'].update(emittedVerbatim=False,
        sha256=hashlib.sha256(after.encode()).hexdigest(),
        desktopCredentialPatch=dict(beforeSHA256=hashlib.sha256(before.encode()).hexdigest(),
                                    before=BEFORE, after=AFTER))
    receipt.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
