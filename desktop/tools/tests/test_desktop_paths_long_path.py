"""Regression coverage for producer paths on POSIX and Windows."""

import importlib.util
import json
import os
from pathlib import Path, PureWindowsPath
import sys
import types
import unittest
from unittest.mock import patch


TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
import desktop_paths


class DesktopLongPathTest(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'the manifest regression exercises POSIX paths')
    def test_posix_producer_reads_manifest(self):
        manifest_path = REPO / 'desktop/upstream-sources.json'
        actual = desktop_paths.long_path(manifest_path)
        self.assertEqual(actual, manifest_path)
        self.assertEqual(json.loads(actual.read_text(encoding='utf-8'))['upstreamRepository'], 'jay3-yy/BiliPai')

        spec = importlib.util.spec_from_file_location(
            'dynamic_reply_protocol_for_long_path', TOOLS / 'extract-upstream-dynamic-reply-protocol.py'
        )
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        self.assertEqual(producer.load_pinned_sources(REPO, []), ({}, []))

    def test_windows_drive_unc_and_existing_prefix(self):
        fake_os = types.SimpleNamespace(name='nt', fspath=os.fspath)
        with patch.object(desktop_paths, 'os', fake_os), patch.object(desktop_paths, 'Path', PureWindowsPath):
            for source, expected in (
                (r'C:\a\b', r'\\?\C:\a\b'),
                (r'\\server\share\x', r'\\?\UNC\server\share\x'),
                (r'\\?\C:\a', r'\\?\C:\a'),
                (r'\\?\UNC\server\share\x', r'\\?\UNC\server\share\x'),
            ):
                with self.subTest(source=source):
                    self.assertEqual(str(desktop_paths.long_path(source)), expected)


if __name__ == '__main__':
    unittest.main()
