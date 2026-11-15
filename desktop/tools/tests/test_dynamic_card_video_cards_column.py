from pathlib import Path
import importlib.util,sys,unittest
sys.dont_write_bytecode=True
REPO=next(p for p in Path(__file__).resolve().parents
 if (p/'desktop/tools/extract-upstream-dynamic-card.py').is_file() and (p/'desktop/upstream-sources.json').is_file())
sys.path.insert(0,str(REPO/'desktop/tools'))
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,REPO/path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
card=module('video_cards_column_sources','desktop/tools/extract-upstream-dynamic-card.py')
# The pinned original modifier chain and the current upstream chain that records
# info bounds before it. Both are the same shared-element chrome seam.
PINNED='''        Column(
            modifier = Modifier.videoCardShellReturnChromeAlpha(
                enabled = useCardShellSharedBounds,
                bvid = archive.bvid,
                sourceRoute = sourceRoute,
            )
        ) {
            Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro))
            VideoCardLargeInfo(
                archive = archive,
            )
        }
'''
CURRENT='''        Column(
            modifier = Modifier.onGloballyPositioned { infoBoundsRef.value = it }
                .videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
        ) {
            Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro))
            VideoCardLargeInfo(
                archive = archive,
            )
        }
'''
HEAD='''        Column {
            Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro))
            VideoCardLargeInfo(
                archive = archive,
'''
PLAIN=HEAD+'''            )
        }
'''
EARLIER='''        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("KEEP_EARLIER_COLUMN")
        }
        Text("KEEP_BETWEEN_CONTAINERS")
'''
COMMENT_SPOOF='''        /*
        Column(
            modifier = Modifier.fillMaxWidth()
        */
        val spoof = """
        Column(
            modifier = Modifier.${"Column("}
        """
        Box(
            modifier = Modifier.onGloballyPositioned { infoBoundsRef.value = it }
                .videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
        ) {
            Spacer(modifier = Modifier.height(1.dp))
            VideoCardLargeInfo(archive = archive)
        }
'''
COMMENTED_SPACER='''        Column(
            modifier = Modifier.onGloballyPositioned { infoBoundsRef.value = it }
                .videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
        ) {
            /*
            Spacer(modifier = Modifier.height(1.dp))
            */
            Text("KEEP_TARGET_CONTENT")
        }
        Box {
            Spacer(modifier = Modifier.height(2.dp))
            Text("KEEP_SIBLING")
        }
'''
EMPTY_BODY='''        Column(
            modifier = Modifier.onGloballyPositioned { infoBoundsRef.value = it }
                .videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
        ) {
        }
        Box {
            Spacer(modifier = Modifier.height(2.dp))
            Text("KEEP_SIBLING")
        }
'''
NESTED_CALL='''        Column(
            modifier = Modifier.then(
                otherModifier.videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
            )
        ) {
            Spacer(modifier = Modifier.height(1.dp))
            VideoCardLargeInfo(archive = archive)
        }
'''
UNALIGNED_BRACE='''        Column(
            modifier = Modifier.onGloballyPositioned { infoBoundsRef.value = it }
                .videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
        ) {
            Spacer(modifier = Modifier.height(1.dp))
            VideoCardLargeInfo(archive = archive)
            }
        Box {
            Text("KEEP_SIBLING")
        }
'''
class DynamicVideoCardsColumnTests(unittest.TestCase):
 def test_info_bounds_recorder_before_chrome_alpha_keeps_the_same_plain_column(self):
  pinned=card.shared_shell_column(PINNED);current=card.shared_shell_column(CURRENT)
  self.assertEqual(pinned,current)
  self.assertEqual(PLAIN,pinned)
 def test_actual_video_cards_info_column_drops_the_shared_element_chain(self):
  body=card.shared_shell_column(card.read(REPO,card.COMP+'VideoCards.kt'))
  self.assertEqual(1,body.count(HEAD))
  self.assertNotIn('.videoCardShellReturnChromeAlpha(',body)
 def test_column_that_no_longer_starts_a_modifier_chain_is_refused(self):
  for source in (CURRENT.replace('.videoCardShellReturnChromeAlpha(','.videoCardShellUnknown('),
                 CURRENT.replace('        Column(\n            modifier = Modifier','        Box(\n            modifier = Modifier')):
   with self.assertRaises(ValueError):card.shared_shell_column(source)
 def test_earlier_column_and_a_box_target_are_refused(self):
  source=EARLIER+CURRENT.replace('        Column(\n            modifier = Modifier','        Box(\n            modifier = Modifier')
  with self.assertRaises(ValueError):card.shared_shell_column(source)
 def test_earlier_column_before_the_target_column_is_preserved(self):
  body=card.shared_shell_column(EARLIER+CURRENT)
  self.assertTrue(body.startswith(EARLIER))
  self.assertTrue(body.endswith(PLAIN))
 def test_real_video_cards_variant_with_a_box_target_is_refused(self):
  original=card.read(REPO,card.COMP+'VideoCards.kt')
  # The pinned original and the current upstream both end the info column's
  # modifier chain with the chrome call. Walk that chain back to the Column that
  # owns it instead of hardcoding either tree's header line.
  lines=original.splitlines(True)
  alpha=[index for index,line in enumerate(lines) if '.videoCardShellReturnChromeAlpha(' in line]
  self.assertEqual(1,len(alpha))
  index=alpha[0]
  while index:
   index-=1
   step=lines[index].strip()
   if step.endswith('Column('):break
   self.assertTrue(step.startswith('modifier = ') or step.startswith('.'),step)
  else:self.fail('the chrome alpha call is not owned by a Column')
  offset=lines[index].index('Column(')
  line_start=sum(map(len,lines[:index]))
  start=line_start+offset
  source=original[:line_start]+EARLIER+lines[index][:offset]+'Box('+original[start+len('Column('):]
  with self.assertRaises(ValueError):card.shared_shell_column(source)
 def test_spacer_outside_the_target_column_is_refused(self):
  source='''        Column(
            modifier = Modifier.onGloballyPositioned { infoBoundsRef.value = it }
                .videoCardShellReturnChromeAlpha(
                    enabled = useCardShellSharedBounds,
                    bvid = archive.bvid,
                    sourceRoute = sourceRoute,
                )
        ) {
            Text("KEEP_TARGET_CONTENT")
        }
        Box {
            Spacer(modifier = Modifier.height(2.dp))
            Text("KEEP_SIBLING")
        }
'''
  with self.assertRaises(ValueError):card.shared_shell_column(source)
 def test_column_with_a_sibling_argument_is_refused(self):
  source=CURRENT.replace('        ) {\n            Spacer(','        , verticalArrangement = Arrangement.Top\n        ) {\n            Spacer(')
  with self.assertRaises(ValueError):card.shared_shell_column(source)
 def test_comment_and_template_spoof_before_a_box_target_is_refused(self):
  with self.assertRaises(ValueError):card.shared_shell_column(COMMENT_SPOOF)
 def test_commented_spacer_with_a_sibling_spacer_is_refused(self):
  with self.assertRaises(ValueError):card.shared_shell_column(COMMENTED_SPACER)
 def test_empty_column_body_is_refused(self):
  with self.assertRaises(ValueError):card.shared_shell_column(EMPTY_BODY)
 def test_chrome_call_nested_in_another_modifier_call_is_refused(self):
  with self.assertRaises(ValueError):card.shared_shell_column(NESTED_CALL)
 def test_unaligned_closing_brace_is_refused(self):
  with self.assertRaises(ValueError):card.shared_shell_column(UNALIGNED_BRACE)
if __name__=='__main__':unittest.main()
