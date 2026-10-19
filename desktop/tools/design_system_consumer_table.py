#!/usr/bin/env python3
"""Consumed file to design-system symbol table at an upstream commit (read-only, no JVM, no network).

Roadmap slice fnd-design-system-leaf-completion starts with this table so the leaf files it carries are chosen by
evidence instead of by the 0.3.8 changelog. For the design-system layer of the upstream commit (default v0.4.6,
d6bf8b16: every top-level declaration of design-system/src/main and design-tokens/src/main) it answers, for every
Kotlin file the Windows tree already consumes:

  * which design-system symbols the file imports (explicit imports; star imports and same-package use are read
    from identifier tokens and marked as such),
  * which upstream file declares each symbol at the commit,
  * whether the Windows tree carries that declaring file at the commit's bytes ("current": some consumed copy has
    exactly those bytes, in any mode; carriedCopies says which and whether it is a whole or a partial use), carries
    only an older whole copy ("older"), only carries pieces of it through producer extractions ("partial"), or does
    not carry it ("absent"),
  * which members of an imported object or class a consumer reads that the carried declaration lacks while the
    commit declares them ("membersMissing", the version skew a plain file comparison cannot show).

Consumed files are the sources the repository already pins: every desktop/upstream-sources.json entry that is not
reference-only (the vendored v0.2.5 tree, by mode), every non-reference compatibility archive leaf of
desktop/tools/compatibility-source-inputs.json (the slice archives), and the desktop-owned Kotlin under
desktop/src/main/kotlin. Only vendored "direct" files and complete (not partialReuse) archive leaves count as
carried definers; extracted, selected or adapted files are reported as "partial".

The tool reads the working tree and local git objects only. It never writes into the checkout (a --json path inside
the repository is refused) and never starts a JVM, Gradle or a network call. The Kotlin scan reuses the lexical
parser of the symbol-closure gate, so it shares that gate's stated limits: no overloads, typealiases, generics,
implicit receivers or visibility; a symbol is found by simple name inside its package.

Usage (from the repository root, one process at a time; about a minute):
  python desktop/tools/design_system_consumer_table.py
  python desktop/tools/design_system_consumer_table.py --focus MediaContrastPalette,AppEntryIconPalette
  python desktop/tools/design_system_consumer_table.py --status absent,older --limit 60
  python desktop/tools/design_system_consumer_table.py --json C:/Temp/consumer-table.json --quiet
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate  # noqa: E402  (the Kotlin lexer/parser of the symbol-closure gate)

SCHEMA_VERSION = 1
COMMIT = gate.TARGET_COMMIT
DESIGN_ROOTS = ('design-system/src/main/', 'design-tokens/src/main/')
SOURCES_FILE = 'desktop/upstream-sources.json'
CATALOG_FILE = 'desktop/tools/compatibility-source-inputs.json'
DESKTOP_KOTLIN = 'desktop/src/main/kotlin'
STATUS_ORDER = ('absent', 'older', 'partial', 'current')
# members every class has or the compiler synthesises; never evidence of version skew
SYNTHETIC_MEMBERS = gate.ANY_MEMBERS | gate.DATA_MEMBERS | gate.ENUM_MEMBERS | frozenset(['Companion'])


class TableError(RuntimeError):
    pass


# --------------------------------------------------------------------------------------
# git and file access (read-only)
# --------------------------------------------------------------------------------------

def _git(repo, *args, data=None):
    try:
        done = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(repo), *args], input=data,
                              capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, 'stderr', b'') or b''
        raise TableError('git %s failed: %s' % (' '.join(args[:2]), detail.decode('utf8', 'replace').strip() or error))
    return done.stdout


def list_commit_files(repo, commit):
    out = _git(repo, 'ls-tree', '-r', '--name-only', commit)
    return sorted(line for line in out.decode('utf8').split('\n') if line)


def read_blobs(repo, commit, paths):
    """{path: (git object id, bytes)} for paths of one commit through a single `git cat-file --batch`."""
    paths = list(paths)
    request = ''.join('%s:%s\n' % (commit, path) for path in paths).encode('utf8')
    data = _git(repo, 'cat-file', '--batch', data=request)
    result, position = {}, 0
    for path in paths:
        end = data.index(b'\n', position)
        header = data[position:end].split()
        if header and header[-1] == b'missing':
            raise TableError('missing at %s: %s' % (commit[:8], path))
        size = int(header[2])
        result[path] = (header[0].decode('ascii'), data[end + 1:end + 1 + size])
        position = end + 1 + size + 1
    return result


def lf_sha256(raw):
    return hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest()


def text_of(raw):
    return raw.decode('utf8').replace('\r\n', '\n').replace('\r', '\n')


# --------------------------------------------------------------------------------------
# design-system layer of the upstream commit
# --------------------------------------------------------------------------------------

class DesignFile:
    __slots__ = ('path', 'blob', 'sha', 'info', 'keys')

    def __init__(self, path, blob, sha, info):
        self.path = path
        self.blob = blob
        self.sha = sha
        self.info = info
        self.keys = [(info.package, d.name) for d in info.decls if d.visibility != 'private' and d.name]


def load_design_system(repo, commit, roots=DESIGN_ROOTS):
    paths = [p for p in list_commit_files(repo, commit) if p.endswith('.kt') and p.startswith(tuple(roots))]
    if not paths:
        raise TableError('no design-system Kotlin files at %s' % commit)
    files, index = {}, defaultdict(list)
    for path, (blob, raw) in read_blobs(repo, commit, paths).items():
        info = gate.parse_kotlin(text_of(raw), path, 'upstream', 'main')
        entry = DesignFile(path, blob, lf_sha256(raw), info)
        files[path] = entry
        for key in entry.keys:
            index[key].append(path)
    return files, index


# --------------------------------------------------------------------------------------
# consumed files of the Windows tree
# --------------------------------------------------------------------------------------

class Consumer:
    __slots__ = ('label', 'origin', 'detail', 'upstream', 'whole', 'info', 'sha')

    def __init__(self, label, origin, detail, upstream, whole, info, sha):
        self.label = label          # repository path of the bytes that were scanned
        self.origin = origin        # vendored | archive | desktop
        self.detail = detail        # vendored mode, archive directory, or ''
        self.upstream = upstream    # upstream path the file derives from ('' for desktop-owned)
        self.whole = whole          # carried verbatim and whole: counts as a definer
        self.info = info
        self.sha = sha


def _read_consumer(repo, label):
    target = Path(repo) / label
    if target.is_symlink() or not target.is_file():
        return None
    raw = target.read_bytes()
    return raw, gate.parse_kotlin(text_of(raw), label, 'consumed', 'main', keep_tokens=True)


def load_consumers(repo):
    repo = Path(repo)
    consumers, notes = [], {'vendoredSkipped': 0, 'archiveSkipped': 0, 'missing': []}
    sources = json.loads((repo / SOURCES_FILE).read_text(encoding='utf8'))['sources']
    for entry in sources:
        path, mode = entry['path'], entry.get('mode', 'direct')
        if not path.endswith('.kt') or mode == 'reference-only':
            notes['vendoredSkipped'] += 1
            continue
        loaded = _read_consumer(repo, path)
        if loaded is None:
            notes['missing'].append(path)
            continue
        consumers.append(Consumer(path, 'vendored', mode, path, mode == 'direct', loaded[1], lf_sha256(loaded[0])))
    catalog = json.loads((repo / CATALOG_FILE).read_text(encoding='utf8'))
    for entry in catalog['inputs']:
        local = entry['localPath']
        if entry.get('kind') != 'adaptedSource' or not local.endswith('.kt') or entry.get('referenceOnly'):
            notes['archiveSkipped'] += 1
            continue
        loaded = _read_consumer(repo, local)
        if loaded is None:
            notes['missing'].append(local)
            continue
        archive = local.rsplit('/', 1)[0]
        whole = not entry.get('partialReuse')
        consumers.append(Consumer(local, 'archive', archive, entry['path'], whole, loaded[1], lf_sha256(loaded[0])))
    desktop = repo / DESKTOP_KOTLIN
    if desktop.is_dir():
        for target in sorted(desktop.rglob('*.kt')):
            label = target.relative_to(repo).as_posix()
            loaded = _read_consumer(repo, label)
            if loaded is not None:
                consumers.append(Consumer(label, 'desktop', '', '', True, loaded[1], lf_sha256(loaded[0])))
    consumers.sort(key=lambda c: (c.origin, c.label))
    return consumers, notes


# --------------------------------------------------------------------------------------
# resolution
# --------------------------------------------------------------------------------------

def resolve_import(parts, index):
    """(package, name, rest) of the longest package prefix whose next segment is a design-system symbol."""
    for k in range(len(parts) - 1, 0, -1):
        key = ('.'.join(parts[:k]), parts[k])
        if key in index:
            return key[0], key[1], parts[k + 1:]
    return None


def _member_usage(toks, local_names):
    """{local name: sorted members read as `Name.member`} (not after another dot, not a call receiver chain)."""
    used = defaultdict(set)
    for i in range(1, len(toks) - 1):
        if toks[i] == '.' and toks[i - 1] in local_names and (i < 2 or toks[i - 2] not in ('.', '?.')) \
                and gate.is_ident(toks[i + 1]):
            used[toks[i - 1]].add(toks[i + 1])
    return used


def _classifier(info, name):
    for decl in info.decls:
        if decl.name == name and decl.classifier:
            return decl
    return None


def _names_used(toks, names_by_package):
    """Heuristic use of names the file did not import: capitalised names always, lowercase only as a call or receiver."""
    found = defaultdict(set)
    for i, token in enumerate(toks):
        hit = names_by_package.get(token)
        if hit is None:
            continue
        following = toks[i + 1] if i + 1 < len(toks) else ''
        if token[:1].isupper() or following in ('(', '.'):
            if i > 0 and toks[i - 1] in ('.', '?.', '::'):
                continue
            for package in hit:
                found[package].add(token)
    return found


def build_table(repo, commit=COMMIT, roots=DESIGN_ROOTS):
    repo = Path(repo)
    design, index = load_design_system(repo, commit, roots)
    consumers, notes = load_consumers(repo)
    packages = defaultdict(set)
    for package, name in index:
        packages[package].add(name)
    name_packages = defaultdict(set)
    for package, names in packages.items():
        for name in names:
            name_packages[name].add(package)

    by_sha = defaultdict(list)
    whole_by_key = defaultdict(list)
    partial_by_key = defaultdict(list)
    for consumer in consumers:
        by_sha[consumer.sha].append(consumer)
        for decl in consumer.info.decls:
            if decl.visibility == 'private' or not decl.name:
                continue
            key = (consumer.info.package, decl.name)
            (whole_by_key if consumer.whole else partial_by_key)[key].append((consumer, decl))

    def file_status(entry):
        if entry.sha in by_sha:
            return 'current'
        if any(key in whole_by_key for key in entry.keys):
            return 'older'
        if any(key in partial_by_key for key in entry.keys):
            return 'partial'
        return 'absent'

    status = {path: file_status(entry) for path, entry in design.items()}
    copies = {}
    for path, entry in design.items():
        found = []
        for key in entry.keys:
            for consumer, _decl in whole_by_key.get(key, []) + partial_by_key.get(key, []):
                found.append((consumer.origin, consumer.label, consumer.detail,
                              'same-bytes' if consumer.sha == entry.sha else 'different-bytes',
                              'whole' if consumer.whole else 'partial'))
        for consumer in by_sha.get(entry.sha, []):
            found.append((consumer.origin, consumer.label, consumer.detail, 'same-bytes',
                          'whole' if consumer.whole else 'partial'))
        copies[path] = sorted(set(found))

    rows = []
    for consumer in consumers:
        info = consumer.info
        local_names, explicit = {}, set()
        for imp in info.imports:
            if imp.star:
                continue
            hit = resolve_import(imp.path.split('.'), index)
            if hit is None:
                continue
            package, name, rest = hit
            local_names[imp.simple] = (package, name, rest, 'import')
            explicit.add(name)
        own = {decl.name for decl in info.decls}
        star_packages = {imp.path for imp in info.imports if imp.star}
        usage = _member_usage(info.toks, {n for n, (_p, _s, rest, _v) in local_names.items() if not rest})
        for local, (package, name, rest, via) in sorted(local_names.items()):
            rows.append(_row(consumer, design, index, status, whole_by_key, package, name, rest, via,
                             sorted(usage.get(local, ())) if not rest else []))
        # same-package and star-imported names that were not imported one by one (token heuristic)
        reach = {p for p in star_packages if p in packages}
        if info.package in packages:
            reach.add(info.package)
        if reach:
            candidates = {n: {p for p in ps if p in reach} for n, ps in name_packages.items()}
            candidates = {n: ps for n, ps in candidates.items() if ps and n not in explicit and n not in own}
            for package, names in sorted(_names_used(info.toks, candidates).items()):
                for name in sorted(names):
                    via = 'same-package' if package == info.package else 'star-import'
                    local_usage = _member_usage(info.toks, {name}).get(name, ())
                    rows.append(_row(consumer, design, index, status, whole_by_key, package, name, [], via,
                                     sorted(local_usage)))

    rows.sort(key=lambda r: (r['declaring'], r['symbol'], r['consumer']))
    by_file = defaultdict(list)
    for row in rows:
        by_file[row['declaring']].append(row)
    files = []
    for path, entry in sorted(design.items()):
        attached = by_file.get(path, [])
        files.append({
            'path': path,
            'blob': entry.blob,
            'sha256Lf': entry.sha,
            'status': status[path],
            'symbols': sorted({name for _p, name in entry.keys}),
            'consumerFiles': len({r['consumer'] for r in attached}),
            'consumersByOrigin': _count_by(attached, 'origin'),
            'importedSymbols': sorted({r['symbol'].rsplit('.', 1)[-1] for r in attached}),
            'carriedCopies': [dict(origin=o, file=f, detail=d, bytes=b, use=u) for o, f, d, b, u in copies[path]],
            'membersMissing': sorted({'%s.%s' % (r['symbol'].rsplit('.', 1)[-1], m)
                                      for r in attached for m in r['membersMissing']}),
        })
    summary = {
        'commit': commit,
        'designSystemFiles': len(design),
        'designSystemSymbols': len(index),
        'consumedFiles': len(consumers),
        'consumedByOrigin': _count_by_label(consumers, lambda c: c.origin),
        'wholeCarriedFiles': sum(1 for c in consumers if c.whole),
        'rows': len(rows),
        'rowsByVia': _count_by(rows, 'via'),
        'filesByStatus': _count_by(files, 'status'),
        'importedFilesByStatus': _count_by([f for f in files if f['consumerFiles']], 'status'),
        'consumerFilesImporting': {s: len({r['consumer'] for r in rows if r['status'] == s}) for s in STATUS_ORDER},
        'membersMissingRows': sum(1 for r in rows if r['membersMissing']),
        'skipped': {'vendoredNotConsumed': notes['vendoredSkipped'], 'archiveReferenceOrResource': notes['archiveSkipped']},
        'consumedFilesMissingOnDisk': sorted(notes['missing']),
    }
    return {'schemaVersion': SCHEMA_VERSION, 'summary': summary, 'files': files, 'rows': rows}


def _row(consumer, design, index, status, whole_by_key, package, name, rest, via, used):
    paths = sorted(index[(package, name)])
    declaring = paths[0]
    entry = design[declaring]
    decl = _classifier(entry.info, name)
    missing = []
    if used and decl is not None and not rest:
        upstream_members = set(decl.members) - SYNTHETIC_MEMBERS
        carried = [d for c, d in whole_by_key.get((package, name), []) if c.sha != entry.sha]
        current = any(c.sha == entry.sha for c, _d in whole_by_key.get((package, name), []))
        if carried and not current:
            for member in used:
                if member in upstream_members and all(member not in d.members for d in carried):
                    missing.append(member)
    return {
        'consumer': consumer.label,
        'origin': consumer.origin,
        'detail': consumer.detail,
        'symbol': '%s.%s' % (package, name) + ('.' + '.'.join(rest) if rest else ''),
        'via': via,
        'declaring': declaring,
        'status': status[declaring],
        'membersUsed': used,
        'membersMissing': sorted(missing),
    }


def _count_by(items, key):
    counts = defaultdict(int)
    for item in items:
        counts[item[key]] += 1
    return dict(sorted(counts.items()))


def _count_by_label(items, function):
    counts = defaultdict(int)
    for item in items:
        counts[function(item)] += 1
    return dict(sorted(counts.items()))


# --------------------------------------------------------------------------------------
# rendering and CLI
# --------------------------------------------------------------------------------------

def render_text(table, statuses=STATUS_ORDER, focus=(), limit=0, consumers=8):
    summary = table['summary']
    lines = ['consumed-file to design-system-symbol table at %s' % summary['commit'][:8],
             '  design-system files %d, symbols %d; consumed Kotlin files %d %s (whole carried definers %d)' % (
                 summary['designSystemFiles'], summary['designSystemSymbols'], summary['consumedFiles'],
                 summary['consumedByOrigin'], summary['wholeCarriedFiles']),
             '  rows %d %s' % (summary['rows'], summary['rowsByVia']),
             '  design-system files by status: %s' % summary['filesByStatus'],
             '  ...of those imported by a consumed file: %s' % summary['importedFilesByStatus'],
             '  consumed files importing a file of each status: %s' % summary['consumerFilesImporting'],
             '  version-skewed member reads (carried declaration lacks the member): %d' % summary['membersMissingRows']]
    chosen = [f for f in table['files'] if f['status'] in statuses and f['consumerFiles'] > 0]
    if focus:
        chosen = [f for f in table['files'] if f['path'].rsplit('/', 1)[-1][:-3] in focus]
    chosen.sort(key=lambda f: (STATUS_ORDER.index(f['status']), -f['consumerFiles'], f['path']))
    if limit and not focus:
        chosen = chosen[:limit]
    lines.append('')
    lines.append('%-8s %5s  %-52s %s' % ('status', 'files', 'design-system file', 'imported symbols (members missing in the carried copy)'))
    for entry in chosen:
        symbols = ', '.join(entry['importedSymbols'][:6]) + (' ...' if len(entry['importedSymbols']) > 6 else '')
        if entry['membersMissing']:
            symbols += '  [missing: %s]' % ', '.join(entry['membersMissing'][:6])
        name = entry['path'].split('/src/main/java/com/android/purebilibili/', 1)[-1]
        lines.append('%-8s %5d  %-52s %s' % (entry['status'], entry['consumerFiles'], name, symbols))
    if focus:
        for entry in chosen:
            lines.append('')
            lines.append('%s (%s, blob %s)' % (entry['path'], entry['status'], entry['blob'][:10]))
            for copy in entry['carriedCopies']:
                lines.append('    carried by %s %s (%s, %s use)' % (copy['origin'], copy['file'], copy['bytes'], copy['use']))
            attached = [row for row in table['rows'] if row['declaring'] == entry['path']]
            for row in attached[:consumers or None]:
                lines.append('    consumer %s [%s %s] %s%s' % (
                    row['consumer'], row['origin'], row['via'], row['symbol'].rsplit('.', 1)[-1],
                    ' members %s' % row['membersUsed'] if row['membersUsed'] else ''))
            if consumers and len(attached) > consumers:
                lines.append('    ... and %d more rows' % (len(attached) - consumers))
    return '\n'.join(lines) + '\n'


def inside(path, root):
    try:
        Path(path).resolve().relative_to(Path(root).resolve())
        return True
    except ValueError:
        return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--repo', default=str(TOOLS.parents[1]), help='repository root (default: this checkout)')
    parser.add_argument('--commit', default=COMMIT, help='upstream commit of the design-system layer (default v0.4.6)')
    parser.add_argument('--json', help='write the whole table here; a path inside the repository is refused')
    parser.add_argument('--focus', default='', help='comma separated design-system file base names (no .kt) to list in full')
    parser.add_argument('--status', default=','.join(STATUS_ORDER[:3]),
                        help='statuses to list in the text table (default: absent,older,partial)')
    parser.add_argument('--limit', type=int, default=0, help='cap the text table rows (0: all)')
    parser.add_argument('--consumers', type=int, default=8, help='consumer rows listed per --focus file (0: all)')
    parser.add_argument('--quiet', action='store_true', help='print only the summary line block')
    args = parser.parse_args(argv)
    statuses = tuple(s for s in args.status.split(',') if s)
    unknown = [s for s in statuses if s not in STATUS_ORDER]
    if unknown:
        parser.error('unknown status: %s' % ', '.join(unknown))
    if args.json and inside(args.json, args.repo):
        parser.error('--json must not point inside the repository (the tool never writes into the checkout)')
    try:
        table = build_table(args.repo, args.commit)
    except (TableError, OSError, KeyError, ValueError) as error:
        print('design-system consumer table: %s' % error, file=sys.stderr)
        return 2
    if args.json:
        Path(args.json).write_bytes((json.dumps(table, indent=2, ensure_ascii=False) + '\n').encode('utf8'))
    focus = tuple(s for s in args.focus.split(',') if s)
    text = render_text(table, statuses, focus, args.limit, args.consumers)
    print(text.split('\n\n', 1)[0] + '\n' if args.quiet else text, end='')
    return 0


if __name__ == '__main__':
    sys.exit(main())
