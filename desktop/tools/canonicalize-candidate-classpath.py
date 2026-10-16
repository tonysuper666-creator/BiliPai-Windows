"""Correct jpackage's recursive JAR list using Gradle's actual main runtime.

Only the generated launcher configuration changes. JS engines, their worker JAR
and source notices remain application resources. No Java or application runs.
This production graph check does not establish source qualification or UI use.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import uuid

import original_root_ui_runtime_binding as binding


def rewrite_cfg(raw, ordered):
    binding.jars.parse_cfg(raw)
    lines = raw.decode('utf-8-sig').splitlines(keepends=True)
    newline = '\r\n' if lines and lines[0].endswith('\r\n') else '\n'
    output = []; section = None; inserted = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('[') and stripped.endswith(']'):
            section = stripped[1:-1]
        if section == 'Application' and stripped.startswith('app.classpath='):
            if not inserted:
                output.extend('app.classpath=$APPDIR\\' + name.replace('/', '\\') + newline for name in ordered)
                inserted = True
            continue
        output.append(line)
    binding.require(inserted, 'Production configuration has no classpath to replace')
    result = ''.join(output).encode('utf-8')
    binding.require(binding.jars.parse_cfg(result)['orderedClasspath'] == ordered,
                    'Generated production classpath differs from the runtime model')
    return result


def canonicalize(candidate, model, source_sha):
    binding.require(re.fullmatch('[0-9a-f]{40}', source_sha) is not None and
                    model.get('schema') == 1 and model.get('sourceSha') == source_sha,
                    'Production runtime model belongs to another source')
    binding.require(isinstance(model.get('mainArtifacts'), list) and 0 < len(model['mainArtifacts']) <= 512 and
                    isinstance(model.get('mainRuntimeClasspath'), list) and
                    0 < len(model['mainRuntimeClasspath']) <= 512,
                    'Invalid production runtime model bounds')
    binding.require(all(model.get(key) is False for key in
                    ('javaExecExecuted', 'applicationExecuted', 'hardwareExecuted')),
                    'The production model must be a nonexecuting Gradle snapshot')
    root = binding.jars.paths.checked_path(candidate)
    cfg = root / 'app/BiliPai Windows.cfg'
    before = binding.jars.bounded_bytes(cfg, binding.jars.MAX_CFG)
    inventory = binding.jars.inventory(root)
    physical = {p.name for p in (root / 'app').iterdir() if p.is_file() and p.suffix.lower() == '.jar'}
    declared = {row['path'] for row in inventory['jars'] if '/' not in row['path']}
    binding.require(physical == declared, 'The generated launcher omitted a packaged host JAR')
    native = [row for row in model['mainArtifacts'] if row.get('group') == 'org.jetbrains.skiko' and
              row.get('name') == 'skiko-awt-runtime-windows-x64']
    binding.require(len(native) == 1, 'The actual Gradle native artifact is missing or ambiguous')
    libraries = binding.production_library_bindings(root, inventory, model,
        Path(native[0]['file']), exclude_resource_jars=True)
    ordered = libraries['orderedClasspath']
    after = rewrite_cfg(before, ordered)
    # Recheck the same generated image before replacing its one configuration.
    for row in inventory['jars']:
        binding.verify_pin(binding.jars.file_digest(root / 'app' / row['path']), row,
                           'A candidate JAR changed during classpath correction')
    binding.require(binding.jars.bounded_bytes(cfg, binding.jars.MAX_CFG) == before,
                    'Production configuration changed during classpath correction')
    if after != before:
        temporary = cfg.with_name(cfg.name + '.' + uuid.uuid4().hex + '.tmp')
        try:
            with temporary.open('xb') as stream:
                stream.write(after); stream.flush(); os.fsync(stream.fileno())
            os.replace(temporary, cfg)
        finally:
            if temporary.exists(): temporary.unlink()
    binding.require(binding.jars.bounded_bytes(cfg, binding.jars.MAX_CFG) == after,
                    'Corrected production configuration readback differs')
    return dict(schema=1, sourceSha=source_sha, passed=True,
        actualGradleMainRuntimeBound=True, orderedProductionClasspath=ordered,
        excludedResourceJars=libraries['excludedResourceJars'], nativeProjection=libraries['nativeProjection'],
        cfgBeforeSHA256=hashlib.sha256(before).hexdigest(), cfgSHA256=hashlib.sha256(after).hexdigest(),
        sourceQualified=False, applicationExecuted=False, uiRuntimeVerified=False, hardwareVerified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--model', required=True, type=Path)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    binding.require(not args.output.exists() and args.output.parent.is_dir(), 'A fresh classpath result path is required')
    raw = binding.jars.bounded_bytes(args.model, binding.MAX_JSON)
    model = binding.json_bytes(raw)
    result = canonicalize(args.candidate, model, args.source_sha)
    result['runtimeModelSHA256'] = hashlib.sha256(raw).hexdigest()
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2); stream.write('\n')


if __name__ == '__main__':
    main()
