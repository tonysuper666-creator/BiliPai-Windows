"""Pure unique-context hunk application; no filesystem mutation or fuzzy matching."""
def apply(current: bytes, patch: bytes) -> bytes:
    source = current.replace(b'\r\n',b'\n').decode('utf8')
    lines = patch.replace(b'\r\n',b'\n').decode('utf8').splitlines(True)
    hunks=[];old=[];new=[];in_hunk=False
    for line in lines:
        if line.startswith('@@ '):
            if in_hunk:hunks.append((''.join(old),''.join(new)))
            old=[];new=[];in_hunk=True
        elif in_hunk:
            if line.startswith(' '):old.append(line[1:]);new.append(line[1:])
            elif line.startswith('-'):old.append(line[1:])
            elif line.startswith('+'):new.append(line[1:])
            else:raise ValueError('Unexpected unified hunk content')
    if in_hunk:hunks.append((''.join(old),''.join(new)))
    if not hunks:raise ValueError('No hunks')
    for before,after in hunks:
        if source.count(before)!=1:raise ValueError('Hunk context is absent or ambiguous; rebase is required')
        source=source.replace(before,after,1)
    return source.encode('utf8')
