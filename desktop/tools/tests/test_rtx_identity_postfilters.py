"""Read-side checks of the actual fixed MPV postfilter predicates and source graph.

Expressions are evaluated from the reviewed C helper, with independent positive
and refusal tables. This executes neither C, MPV, NGX nor a display/GPU path.
The complete upstream fixtures retain their upstream copyright and license.
"""
import ast
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
INPUTS = ROOT / 'desktop/third-party/libmpv/build/rtx-present-v1'
FIXTURES = Path(__file__).resolve().parent / 'fixtures'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def compact(text):
    return re.sub(r'\s+', '', text)


def evaluate_expression(text, values):
    # Only the boolean C subset used in the actual two helper expressions.
    aliases = {
        'mp_filter_command(u->f, &cmd)': 'query_ok',
        'image->params.rotate': 'rotation',
        'image->params.vflip': 'flipped',
        'p->bilipai_autovflip': 'flip_owner',
        'p->bilipai_autorotate': 'rotate_owner',
        'cmd.is_active': 'active',
    }
    for source, destination in aliases.items():
        text = text.replace(source, destination)
    text = re.sub(r'\bu\b', 'owner', text)
    text = re.sub(r'\bimage\b', 'image_present', text)
    text = text.replace('&&', ' and ').replace('||', ' or ')
    text = re.sub(r'!(?!=)', ' not ', text)
    tree = ast.parse(' '.join(text.split()), mode='eval')

    def value(node):
        if isinstance(node, ast.Name) and node.id in values:
            return values[node.id]
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            return not value(node.operand)
        if isinstance(node, ast.BoolOp):
            if isinstance(node.op, ast.And):
                return all(value(child) for child in node.values)
            if isinstance(node.op, ast.Or):
                return any(value(child) for child in node.values)
        if isinstance(node, ast.Compare) and len(node.ops) == len(node.comparators) == 1:
            left, right = value(node.left), value(node.comparators[0])
            if isinstance(node.ops[0], ast.NotEq):
                return left != right
        raise ValueError('Unreviewed native identity expression')
    return bool(value(tree.body))


class ActualIdentityPostfilter:
    def __init__(self, source):
        found = re.findall(
            r'static bool bilipai_is_identity_auto_filter\([^{}]+\)\s*\{', source)
        if len(found) != 1:
            raise ValueError('Missing or ambiguous native identity helper')
        pattern = (r'\s*struct chain \*p = u->p;\s*if \((.*?)\)\s*return false;\s*'
                   r'struct mp_filter_command cmd = \{\.type = MP_FILTER_COMMAND_IS_ACTIVE\};\s*'
                   r'return (.*?);\s*')
        # The initializer has braces, so include it explicitly in the source span.
        first = source.index('static bool bilipai_is_identity_auto_filter(')
        last = source.index('\n}\n', first)
        body = re.sub(r'//[^\n]*', '', source[source.index('{', first)+1:last])
        match = re.fullmatch(pattern, body, flags=re.S)
        if match is None:
            raise ValueError('Unreviewed native identity helper body')
        self.refuse, self.accept = match.groups()

    def allows(self, **overrides):
        values = dict(owner=1, flip_owner=1, rotate_owner=2, image_present=True,
                      rotation=0, flipped=False, query_ok=True, active=False)
        values.update(overrides)
        return (not evaluate_expression(self.refuse, values) and
                evaluate_expression(self.accept, values))


class RtxIdentityPostfilterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = json.loads((INPUTS / 'presentation-edits.json').read_bytes())
        cls.row = next(row for row in cls.rows if row['path'] == 'filters/f_output_chain.c')
        cls.original = (FIXTURES / 'mpv-output-chain-69e63f.c').read_bytes()
        if sha(cls.original) != cls.row['beforeSha256']:
            raise ValueError('Complete fixed upstream output chain changed')
        cls.source = cls.original.decode()
        for edit in cls.row['edits']:
            if cls.source.count(edit['old']) != edit['count']:
                raise ValueError('Native complete-file source anchor changed')
            cls.source = cls.source.replace(edit['old'], edit['new'])
        if sha(cls.source.encode()) != cls.row['afterSha256']:
            raise ValueError('Actual native output-chain identity changed')
        cls.actual = ActualIdentityPostfilter(cls.source)

    def test_complete_fixed_upstream_chain_has_an_exact_inverse(self):
        source = self.source
        for edit in reversed(self.row['edits']):
            self.assertEqual(source.count(edit['new']), edit['count'])
            source = source.replace(edit['new'], edit['old'])
        self.assertEqual(source.encode(), self.original)
        self.assertEqual(len(self.rows), 24)

    def test_both_owned_inactive_zero_orientation_wrappers_keep_identity(self):
        for owner in (1, 2):
            with self.subTest(owner=owner):
                self.assertTrue(self.actual.allows(owner=owner))

    def test_arbitrary_filters_and_spoofed_names_do_not_get_the_exception(self):
        for owner in (0, 3, 9):
            self.assertFalse(self.actual.allows(owner=owner))
        # The positive helper is based on owned pointers, never filter labels.
        first = self.source.index('static bool bilipai_is_identity_auto_filter(')
        last = self.source.index('\n}\n', first)
        self.assertNotIn('strcmp', self.source[first:last])
        self.assertNotIn('->name', self.source[first:last])

    def test_rotation_flip_active_unknown_and_missing_frame_are_refused(self):
        for change in ({'rotation': 90}, {'rotation': -90}, {'rotation': 360},
                       {'flipped': True}, {'active': True}, {'query_ok': False},
                       {'image_present': False}):
            with self.subTest(change=change):
                self.assertFalse(self.actual.allows(**change))

    def test_actual_wrapper_rechecks_identity_before_and_after_processing(self):
        code = compact(self.source)
        self.assertIn('u!=p->input&&u!=p->output&&u!=p->convert_wrapper&&'
                      '!bilipai_is_identity_auto_filter(u,frame.data)', code)
        check = ('if(frame.type==MP_FRAME_VIDEO&&'
                 '(u==p->bilipai_autovflip||u==p->bilipai_autorotate)&&'
                 '!bilipai_is_identity_auto_filter(u,frame.data))'
                 'bilipai_clear_frame_provenance(frame.data);')
        output = code.index('mp_pin_out_read(u->f->pins[1])')
        self.assertLess(output, code.index(check))
        self.assertLess(code.index(check), code.index('mp_pin_in_write(f->ppins[1],frame)'))
        self.assertIn('!mp_image_bilipai_token_matches(frame.data)', code)
        self.assertIn('p->bilipai_autovflip=f;', code)
        self.assertIn('p->bilipai_autorotate=f;', code)

    def test_real_builtin_filters_report_transform_activity_and_have_identity_paths(self):
        raw = (FIXTURES / 'mpv-auto-filters-69e63f.c').read_bytes()
        self.assertEqual(sha(raw), '38d9ef99efc3b814443c74e62289f68444dded3643c21b9e8e24cf9ea317f663')
        code = compact(raw.decode())
        for function in ('rotate_command', 'vflip_command'):
            first = code.index('staticbool' + function + '(')
            last = code.index('returnfalse;}', first)
            self.assertIn('cmd->is_active=!!p->sub.filter;', code[first:last])
        self.assertIn('if(!vflip||(info&&info->vflip)){mp_subfilter_continue(&p->sub);return;}', code)
        self.assertIn('if(rotate==0||(info&&info->rotate90&&!(rotate%90)))'
                      '{mp_subfilter_continue(&p->sub);return;}', code)
        self.assertIn('p->pre_filters,p->user_filters,p->post_filters', compact(self.source))

    def test_renderer_queue_display_and_token_matching_guards_are_byte_unchanged(self):
        expected = {
            'video/mp_image.c': '08c08c3c3ca6439ecea83daab41728962e9759e10432517b3c6b256cff834e5f',
            'video/out/d3d11/context.c': 'a303c888d8cdede01fdf987eb082b096b3132f5b3f69f5fd8c02d0b22d668946',
            'video/out/gpu/video.c': '70995b2f27cc9036c817082807603fb426e379df8da5b2c37cbc02d807cc08c3',
        }
        for path, digest in expected.items():
            self.assertEqual(next(row for row in self.rows if row['path'] == path)['afterSha256'], digest)

    def test_manifest_helper_build_and_consumer_identity_chain_match(self):
        manifest_raw = (INPUTS / 'bilipai-rtx-presentation-source-manifest.json').read_bytes()
        fixed = json.loads((INPUTS / 'fixed-inputs.json').read_bytes())
        manifest = json.loads(manifest_raw)
        self.assertEqual(fixed['filterSourceManifestSha256'], sha(manifest_raw))
        self.assertEqual(manifest['upstreamEditsSha256'], sha((INPUTS / 'presentation-edits.json').read_bytes()))
        self.assertEqual(fixed['upstreamEditsSha256'], manifest['upstreamEditsSha256'])
        self.assertEqual(fixed['buildPatchHelperSha256'], sha((INPUTS / 'apply-rtx-presentation-source.py').read_bytes()))
        consumer = (ROOT / 'desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopVeyraPrivateComponent.kt').read_text()
        for digest in (sha(manifest_raw), fixed['buildPatchHelperSha256'], manifest['upstreamEditsSha256']):
            self.assertIn(digest, consumer)
        receipt = json.loads((FIXTURES / 'mpv-rtx-present-v1-native-patch-receipt.json').read_bytes())
        self.assertEqual(receipt['filterSourceManifestSha256'], sha(manifest_raw))
        self.assertEqual(receipt['sourcePatchHelperSha256'], fixed['buildPatchHelperSha256'])

    def test_native_workflow_runs_this_source_gate_before_any_build(self):
        text = (ROOT / '.github/workflows/windows-mpv-rtx-core-manual.yml').read_text()
        command = 'python3 -B -m unittest discover -s desktop/tools/tests -p test_rtx_identity_postfilters.py -v'
        self.assertEqual(text.count(command), 1)
        self.assertLess(text.index(command), text.index('name: Download exact independently reviewed own HOST snapshot'))


if __name__ == '__main__':
    unittest.main()
