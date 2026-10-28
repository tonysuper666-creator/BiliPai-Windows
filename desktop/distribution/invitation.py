"""Service-user invitation writer. OS flock serializes writers without stale locks."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys
from publish import atomic, require


def main():
    os.umask(0o077)
    require(len(sys.argv) in (3, 4), 'Usage: invitation.mjs create|revoke <label> [dev|stable]')
    operation, label = sys.argv[1:3]
    channel = sys.argv[3] if len(sys.argv) == 4 else 'stable'
    require(operation in ('create', 'revoke') and re.fullmatch(r'[A-Za-z0-9_-]{1,40}', label) and
            channel in ('dev', 'stable'), 'Invalid invitation operation/label/channel')
    root = Path(os.environ.get('BILIPAI_DISTRIBUTION_ROOT', '/opt/bilipai-distribution/data')).resolve()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(root.stat().st_uid == os.geteuid(), 'Run invitations as the distribution data owner/service user')
    directory = root / 'private'
    directory.mkdir(mode=0o700, exist_ok=True)
    file = directory / 'invitations.json'
    descriptor = os.open(directory / 'invitations.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        require(not file.is_symlink(), 'Invitation registry must not be a symlink')
        require(not file.exists() or file.stat().st_size <= 1024 * 1024, 'Invitation registry is too large')
        registry = json.loads(file.read_text()) if file.exists() else {}
        if operation == 'create':
            require(not any(i['label'] == label for i in registry.values()), 'Label already exists; use a new label')
            code = secrets.token_urlsafe(24)
            registry[hashlib.sha256(code.encode()).hexdigest()] = {'label': label, 'enabled': True, 'channels': [channel]}
            atomic(file, (json.dumps(registry) + '\n').encode())
            print(code)
        else:
            matches = [i for i in registry.values() if i['label'] == label]
            require(len(matches) == 1, 'Invitation label not found or ambiguous')
            matches[0]['enabled'] = False
            atomic(file, (json.dumps(registry) + '\n').encode())
            print('revoked ' + label)


if __name__ == '__main__':
    main()
