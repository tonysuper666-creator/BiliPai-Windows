import copy
import json
import re
import tempfile
import unittest
from pathlib import Path

import ux_model
import ux_reading_model as model


class DynamicReadingModelTest(unittest.TestCase):
    def test_repository_is_in_sync(self):
        self.assertEqual([], model.problems())

    def test_changed_contract_requires_regeneration(self):
        contract = copy.deepcopy(ux_model.load())
        contract["reading"]["dynamic"]["column_max"] = 761
        self.assertIn("DYNAMIC_READING_COLUMN_MAX = 761", model.render(contract))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.json"
            path.write_text(json.dumps(contract), encoding="utf-8")
            self.assertTrue(model.problems(path, model.KOTLIN_PATH))

    def test_missing_or_non_integer_values_are_rejected(self):
        for key, _ in model.FIELDS:
            for invalid in (None, True, 1.5, "1"):
                with self.subTest(key=key, value=invalid):
                    contract = copy.deepcopy(ux_model.load())
                    if invalid is None:
                        del contract["reading"]["dynamic"][key]
                    else:
                        contract["reading"]["dynamic"][key] = invalid
                    with self.assertRaisesRegex(ValueError, key):
                        model.render(contract)

    def test_constants_are_only_defined_in_generated_file(self):
        root = ux_model.REPO / "desktop/src/main/kotlin"
        pattern = re.compile(r"\bconst\s+val\s+DYNAMIC_READING_\w+")
        hits = [(path, pattern.findall(path.read_text(encoding="utf-8")))
                for path in root.rglob("*.kt")]
        hits = [(path, matches) for path, matches in hits if matches]
        self.assertEqual([(model.KOTLIN_PATH, 6)], [(path, len(matches)) for path, matches in hits])

    def test_generated_header(self):
        header = model.KOTLIN_PATH.read_text(encoding="utf-8").splitlines()[0]
        self.assertIn("Generated", header)
        self.assertIn("Do not edit", header)


if __name__ == "__main__":
    unittest.main()
