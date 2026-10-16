"""Structural byte fixtures only; these classes are never loaded or executed."""
from pathlib import Path
from tempfile import TemporaryDirectory
from collections import deque
import io
import struct
import unittest
import sys
from unittest.mock import patch
TOOLS = Path(__file__).resolve().parent
if not (TOOLS / 'original_root_ui_class_closure.py').is_file(): TOOLS = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import original_root_ui_class_closure as module


def u2(value): return struct.pack('>H', value)
def u4(value): return struct.pack('>I', value)


def compiled(name, source, references=(), descriptors=(), literals=(), duplicate_source=False):
    pool = []
    def utf(text):
        units = text.encode('utf-16-be', errors='surrogatepass')
        raw = b''.join(chr(int.from_bytes(units[i:i + 2], 'big')).encode('utf-8', errors='surrogatepass')
                       for i in range(0, len(units), 2)).replace(b'\x00', b'\xc0\x80')
        pool.append(b'\x01' + u2(len(raw)) + raw)
        return len(pool)
    def cls(text):
        slot = utf(text)
        pool.append(b'\x07' + u2(slot))
        return len(pool)
    own, parent = cls(name), cls('java/lang/Object')
    for reference in references: cls(reference)
    for value in [*descriptors, *literals]: utf(value)
    attributes = []
    if source is not None:
        label, filename = utf('SourceFile'), utf(source)
        attributes.append(u2(label) + u4(2) + u2(filename))
        if duplicate_source: attributes.extend(attributes)
    return (b'\xca\xfe\xba\xbe' + u2(0) + u2(65) + u2(len(pool) + 1) + b''.join(pool)
        + u2(0x21) + u2(own) + u2(parent) + u2(0) + u2(0) + u2(0)
        + u2(len(attributes)) + b''.join(attributes))


class ClosureTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entry = 'sample/ActualFixture'

    def put(self, name, source='ActualFixture.kt', **extra):
        path = module.absolute_path(self.root / (name + '.class'))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(compiled(name, source, **extra))
        return path

    def selected(self, production=()):
        return module.select_closure(module.index_classes([self.root]), production, self.entry)

    def test_source_unit_keeps_top_level_facade_and_nested_adapters(self):
        for name in (self.entry, 'sample/ActualFixtureKt', 'sample/ActualFixture$inlined$-filter$1'):
            self.put(name)
        self.assertEqual(set(self.selected()), {self.entry, 'sample/ActualFixtureKt', 'sample/ActualFixture$inlined$-filter$1'})

    def test_descriptor_only_reference_and_helper_peers_are_traversed(self):
        self.put(self.entry, descriptors=['(Ljava/lang/String;Lsample/Helper;)V'])
        self.put('sample/Helper', 'Helper.kt')
        self.put('sample/Helper$1', 'Helper.kt')
        self.assertEqual(len(self.selected()), 3)

    def test_kotlin_backtick_space_and_unicode_class_names_are_preserved(self):
        helper = 'sample/Helper$download fails 完成😀$1'
        self.put(self.entry, descriptors=['(L' + helper + ';)V'])
        self.put(helper, 'Helper.kt')
        self.assertIn(helper, self.selected())

    def test_modified_utf8_nul_and_surrogate_literals_decode_losslessly(self):
        symbols = module.class_symbols(compiled(self.entry, 'Actual.kt', literals=['\x00😀', '\ud800']))
        self.assertEqual(symbols.name, self.entry)
        self.assertEqual(module.modified_utf8(b'\xc0\x80'), '\x00')
        for broken in (b'\x00', b'\xf0\x9f\x98\x80', b'\xc1\x80'):
            with self.assertRaises(module.ClassFormatError): module.modified_utf8(broken)

    def test_reflection_literal_reaches_present_compiled_test_helper(self):
        self.put(self.entry, literals=['sample.Helper'])
        self.put('sample/Helper', 'Helper.kt')
        self.assertIn('sample/Helper', self.selected())

    def test_unrelated_test_and_same_filename_in_another_package_are_excluded(self):
        self.put(self.entry)
        self.put('sample/Unrelated', 'Other.kt')
        self.put('other/Actual', 'Actual.kt')
        self.assertEqual(list(self.selected()), [self.entry])

    def test_inline_library_source_file_does_not_collect_unrelated_callers(self):
        inline = self.entry + '$inlined$effect$1'
        self.put(self.entry, references=[inline])
        self.put(inline, 'Effects.kt')
        self.put('sample/UnrelatedTest$inlined$effect$1', 'Effects.kt')
        self.assertEqual(set(self.selected()), {self.entry, inline})

    def test_exact_composable_singleton_does_not_collect_other_files(self):
        self.put(self.entry)
        singleton = 'sample/ComposableSingletons$ActualFixtureKt'
        self.put(singleton)
        self.put(singleton + '$lambda$1')
        self.put('sample/ComposableSingletons$OtherKt', 'Other.kt')
        self.assertEqual(set(self.selected()), {self.entry, singleton, singleton + '$lambda$1'})

    def test_array_and_generic_descriptors_reach_helpers(self):
        self.put(self.entry, references=['[Lsample/ArrayHelper;'], descriptors=['Ljava/util/List<Lsample/GenericHelper;>;'])
        self.put('sample/ArrayHelper', 'Array.kt')
        self.put('sample/GenericHelper', 'Generic.kt')
        self.assertEqual(len(self.selected()), 3)

    def test_cycle_terminates_without_losing_classes(self):
        self.put(self.entry, references=['sample/Helper'])
        self.put('sample/Helper', 'Helper.kt', references=[self.entry])
        self.assertEqual(len(self.selected()), 2)

    def test_dense_reference_graph_enqueues_each_compiled_class_once(self):
        names = [self.entry] + ['sample/Helper' + str(i) for i in range(48)]
        for position, name in enumerate(names):
            self.put(name, 'Helper' + str(position) + '.kt', references=names)
        enqueued = []
        class ObservedQueue(deque):
            def __init__(self, values):
                enqueued.extend(values)
                super().__init__(values)
            def append(self, value):
                enqueued.append(value)
                super().append(value)
        with patch.object(module, 'deque', ObservedQueue):
            self.assertEqual(set(self.selected()), set(names))
        self.assertEqual(len(enqueued), len(names))
        self.assertEqual(len(set(enqueued)), len(names))

    def test_unterminated_long_L_literal_remains_a_bounded_structural_input(self):
        literal = 'L' * 65535
        symbols = module.class_symbols(compiled(self.entry, 'Actual.kt', literals=[literal]))
        self.assertEqual(symbols.name, self.entry)
        self.assertEqual(module.descriptor_references(literal), set())

    def test_missing_actual_entry_is_rejected(self):
        self.put('sample/Other')
        with self.assertRaisesRegex(ValueError, 'entry class'): self.selected()

    def test_duplicate_compiled_identity_is_rejected(self):
        self.put(self.entry)
        with self.assertRaisesRegex(ValueError, 'Duplicate'): module.index_classes([self.root, self.root])

    def test_selected_test_cannot_shadow_production(self):
        self.put(self.entry, references=['sample/Helper'])
        self.put('sample/Helper', 'Helper.kt')
        with self.assertRaisesRegex(ValueError, 'shadow'): self.selected(['sample/Helper'])

    def test_reached_class_without_source_file_is_rejected(self):
        self.put(self.entry, references=['sample/Helper'])
        self.put('sample/Helper', None)
        with self.assertRaisesRegex(ValueError, 'SourceFile'): self.selected()

    def test_bad_source_path_or_duplicate_source_is_rejected(self):
        for name in ('../../Actual.kt', '/Actual.kt', 'Actual..kt'):
            with self.subTest(name=name), self.assertRaises(module.ClassFormatError):
                module.class_symbols(compiled(self.entry, name))
        with self.assertRaisesRegex(module.ClassFormatError, 'Ambiguous'):
            module.class_symbols(compiled(self.entry, 'Actual.kt', duplicate_source=True))

    def test_renamed_compiled_file_is_rejected(self):
        path = self.put(self.entry)
        path.rename(path.with_name('Other.class'))
        with self.assertRaisesRegex(ValueError, 'file path'): module.index_classes([self.root])

    def test_truncation_and_trailing_bytes_are_rejected(self):
        raw = compiled(self.entry, 'Actual.kt')
        for broken in (raw[:9], raw[:-1], raw + b'x'):
            with self.subTest(length=len(broken)), self.assertRaises(module.ClassFormatError): module.class_symbols(broken)

    def test_future_target_and_preview_classes_are_rejected(self):
        raw = bytearray(compiled(self.entry, 'Actual.kt'))
        for position, value in ((6, 66), (4, 65535)):
            broken = raw.copy()
            broken[position:position + 2] = u2(value)
            with self.assertRaisesRegex(module.ClassFormatError, 'target'): module.class_symbols(broken)

    def test_bounded_index_rejects_excess_without_loading_jvm(self):
        self.put(self.entry)
        self.put('sample/Helper', 'Helper.kt')
        with patch.object(module, 'MAX_CLASSES', 1), self.assertRaisesRegex(ValueError, 'bound'):
            module.index_classes([self.root])

    def test_real_long_compiled_path_is_read_without_global_windows_policy_changes(self):
        helper = 'sample/Helper$' + 'x' * 210
        self.put(self.entry, references=[helper])
        path = self.put(helper, 'Helper.kt')
        self.addCleanup(path.unlink, missing_ok=True)
        self.assertGreater(len(str(path)), 260)
        self.assertIn(helper, self.selected())

    def test_export_readback_detects_class_change(self):
        path = self.put(self.entry)
        selected = self.selected()
        module.verify_unchanged(selected)
        path.write_bytes(path.read_bytes() + b'x')
        with self.assertRaisesRegex(ValueError, 'changed'): module.verify_unchanged(selected)

    def test_growing_class_uses_a_bounded_read_before_rejection(self):
        path = self.put(self.entry)
        original_size = path.stat().st_size
        oversized = b'x' * (module.MAX_CLASS_BYTES + 128)
        requested = []
        class ObservedStream(io.BytesIO):
            def read(self, size=-1):
                self_outer.assertGreater(size, 0)
                self_outer.assertLessEqual(size, module.MAX_CLASS_BYTES + 1)
                requested.append(size)
                return super().read(size)
        self_outer = self
        with patch.object(Path, 'open', return_value=ObservedStream(oversized)):
            with self.assertRaisesRegex(ValueError, 'bound'):
                module.bounded_class_bytes(path, original_size)
        self.assertEqual(requested, [module.MAX_CLASS_BYTES + 1])

    def test_index_and_export_verifier_do_not_use_unbounded_read_bytes(self):
        self.put(self.entry)
        with patch.object(Path, 'read_bytes', side_effect=AssertionError('Unbounded read is forbidden')):
            selected = self.selected()
            module.verify_unchanged(selected)


if __name__ == '__main__': unittest.main(verbosity=2)
