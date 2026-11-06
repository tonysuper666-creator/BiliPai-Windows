"""Inventory identities must authenticate before selecting legacy source bytes."""
from pathlib import Path
import unittest
from unittest.mock import patch

from v025_source_paths import registered_source_sha256


class RegisteredSourceReadpointTest(unittest.TestCase):
    def test_live_and_archived_inventories_select_the_same_reviewed_legacy_bytes(self):
        catalog = {"paths": {"app/A.kt": {"sha256": "live", "legacySha256": "old"}}}
        with patch("v025_source_paths._catalog", return_value=catalog):
            for inventory in ("live", "old"):
                with self.subTest(inventory=inventory):
                    self.assertEqual("old", registered_source_sha256(
                        Path(".legacy-root"), {"path": "app/A.kt", "sha256": inventory}))
            self.assertEqual("live", registered_source_sha256(
                Path("live"), {"path": "app/A.kt", "sha256": "live"}))
            for root, inventory in ((".legacy-root", "tampered"), ("live", "old")):
                with self.subTest(root=root, inventory=inventory):
                    with self.assertRaisesRegex(ValueError, "Registered original source digest mismatch"):
                        registered_source_sha256(Path(root), {"path": "app/A.kt", "sha256": inventory})

