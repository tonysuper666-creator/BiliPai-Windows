from pathlib import Path
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
SCRIPT=HERE/'extract-upstream-settings-entries.py'
if not SCRIPT.exists():SCRIPT=REPO/'desktop/tools/extract-upstream-settings-entries.py'
spec=importlib.util.spec_from_file_location('settings_entries',SCRIPT)
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
host,media,parser=tool.helpers(REPO)

def tokens(source):return [token[0] for token in parser.kotlin_tokens(source)]

def entry_calls(source):
    lex=parser.kotlin_tokens(source);result=[]
    for start,t in enumerate(lex[:-1]):
        if t[0]!='SettingsDetailEntry' or lex[start+1][0]!='(':continue
        end=start+1;depth=1
        while depth:
            end+=1;depth+=(lex[end][0]=='(')-(lex[end][0]==')')
        result.append([x[0] for x in lex[start:end+1]])
    return result

def block_tokens(lex, opening):
    assert lex[opening] == '{'
    end = opening + 1; depth = 1
    while depth:
        depth += (lex[end] == '{') - (lex[end] == '}')
        end += 1
    return lex[opening + 1:end - 1]

def original_playback_body_tokens(source):
    lex = tokens(source); removals = []
    for index, token in enumerate(lex[:-1]):
        if token == 'SettingsRootCategoryEntranceSection' and lex[index + 1] == '{':
            contents = block_tokens(lex, index + 1)
            removals.extend(((index, index + 2), (index + 2 + len(contents), index + 3 + len(contents))))
    assert len(removals) == 4
    for begin, end in reversed(removals):
        del lex[begin:end]
    for _ in range(2):
        index = lex.index('actions')
        assert lex[index:index + 3] == ['actions', '.', 'onPlaybackClick']
        lex[index:index + 3] = ['onPlaybackClick']
    assert 'actions' not in lex
    return lex

class SettingsEntryExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='settings-entry-extract-')
        self.output=Path(self.temp.name)
        self.files={p.name:p.read_text(encoding='utf-8') for p in tool.generate(REPO,self.output)}
    def tearDown(self):self.temp.cleanup()

    def test_shared_data_and_group_are_entire_original_declarations(self):
        original=tool.read(REPO);source=self.files['SettingsDetailEntries.kt']
        self.assertEqual(media.data_class(source,'SettingsDetailEntry',parser),media.data_class(original,'SettingsDetailEntry',parser))
        self.assertEqual(media.function(source,'SettingsDetailGroup',parser),media.function(original,'SettingsDetailGroup',parser))
        self.assertNotIn('fun SettingsCardGroup',source)
        self.assertNotIn('fun SettingsAdaptiveDivider',source)

    def test_entry_renderer_only_changes_android_painter_binding(self):
        source=media.function(self.files['SettingsDetailEntries.kt'],'SettingsDetailEntrySection',parser)
        self.assertEqual(source.replace('rememberVectorPainter(DesktopSettingsVectors.vector(it))','painterResource(id = it)'),
            media.function(tool.read(REPO),'SettingsDetailEntrySection',parser))
        self.assertLess(source.index('SettingsSearchFocusController.submit'),source.index('entry.onClick()'))
        self.assertIn('SettingsSearchFocusController.clear()',source)

    def test_two_canonical_playback_calls_are_original_including_decoder_focus(self):
        original=entry_calls(tool.original_playback_branch(REPO))
        generated=entry_calls(self.files['SettingsPlaybackCategoryEntries.kt'])
        # Exact lexical statements; only the explicitly supplied Windows callback differs.
        source_calls=[x for x in original]
        expected=[]
        for call in source_calls:
            at=call.index('actions')
            self.assertEqual(call[at:at+3],['actions','.','onPlaybackClick'])
            expected.append(call[:at]+['onPlaybackClick']+call[at+3:])
        self.assertEqual(generated,expected)
        self.assertEqual(len(generated),2)
        self.assertIn('PLAYBACK_DECODER',generated[0]);self.assertNotIn('PLAYBACK_NETWORK',generated[0])
        self.assertIn('PLAYBACK_INTERACTION',generated[1])
        # All canonical branch statements survive the two entrance-wrapper and
        # callback adaptations. v025 has no old synthetic 12dp Spacer here.
        whole = media.function(self.files['SettingsPlaybackCategoryEntries.kt'],
                               'SettingsPlaybackCategoryEntrySection', parser)
        generated_body = tokens(whole)
        column = generated_body.index('Column')
        self.assertEqual(block_tokens(generated_body, column + 1),
                         original_playback_body_tokens(tool.original_playback_branch(REPO)))
        self.assertNotIn('SettingsRootCategoryEntranceSection',self.files['SettingsPlaybackCategoryEntries.kt'])
        self.assertNotIn('SettingsRootCategoryState',self.files['SettingsPlaybackCategoryEntries.kt'])
        self.assertNotIn('SettingsRootCategoryActions',self.files['SettingsPlaybackCategoryEntries.kt'])

    def test_single_existing_source_identity_and_two_stable_outputs_without_new_assets(self):
        first={p.name:p.read_bytes() for p in self.output.rglob('*.kt')}
        second={p.name:p.read_bytes() for p in tool.generate(REPO,self.output)}
        self.assertEqual(first,second)
        self.assertEqual(set(first),{'SettingsDetailEntries.kt','SettingsPlaybackCategoryEntries.kt'})
        self.assertEqual(len(tool.inventory(REPO)),1)
        self.assertEqual(tool.inventory(REPO)[0]['path'],tool.SOURCE)

    def test_gained_upstream_state_dependency_is_rejected_instead_of_defaulted(self):
        original=tool.original_playback_branch
        with patch.object(tool,'original_playback_branch',side_effect=lambda repo:original(repo).replace('actions.onPlaybackClick','state.newRequiredField',1)):
            with self.assertRaises(ValueError):tool.generate(REPO,self.output)

if __name__=='__main__':unittest.main()
