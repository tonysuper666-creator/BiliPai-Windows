import hashlib, importlib.util, json, os, tempfile, unittest
from unittest.mock import patch
from pathlib import Path

REPO=next(path for path in Path(__file__).resolve().parents
 if (path/"desktop/tools/extract-upstream-story-topic.py").is_file() and (path/"desktop/upstream-sources.json").is_file())
LIVE=Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO)
SCRIPT=REPO/"desktop/tools/extract-upstream-story-topic.py"
if not SCRIPT.exists(): SCRIPT=REPO/"desktop/.local/story-topic-parity/extract-story-topic-platform.py"
spec=importlib.util.spec_from_file_location("story_topic",SCRIPT); module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class OriginalStoryTopicExtractTest(unittest.TestCase):
    def test_inventory_is_unique_lf_and_mode_scoped(self):
        entries=module.inventory(REPO)
        expected={module.BASE+path:mode for path,mode in [
            ('feature/story/StoryFeedPolicy.kt','direct'),
            ('feature/search/TopicDetailVisualPolicy.kt','direct'),
            ('navigation/PortraitStoryNavigationPolicy.kt','direct'),
            ('data/repository/TopicRepository.kt','platform-adapter-reference'),
            ('feature/video/ui/pager/PortraitPagerSwitchPolicy.kt','platform-adapter-reference'),
            ('feature/story/StoryViewModel.kt','policy-extract'),
            ('feature/story/StoryScreen.kt','policy-extract'),
            ('feature/search/TopicDetailViewModel.kt','policy-extract'),
            ('feature/dynamic/components/DynamicRichTextPolicy.kt','policy-extract'),
        ]}
        self.assertEqual(len(entries),len({item['path'] for item in entries}))
        self.assertEqual(expected,{item['path']:item['mode'] for item in entries})
        for item in entries:
            self.assertEqual(item["sha256"],hashlib.sha256(module.read(REPO,item["path"]).encode()).hexdigest())
    def test_product_omits_direct_copies(self):
        with tempfile.TemporaryDirectory() as directory:
            files=module.generate(REPO,Path(directory))
            expected={'TopicRepository.kt','PortraitPagerSwitchPolicy.kt','StoryUiState.kt',
                'StoryScreen.kt','TopicDetailStatePolicy.kt','DynamicTopicLinkPolicy.kt','StoryFeedPolicy.kt','StoryModels.kt'}
            self.assertEqual(expected,{path.name for path in files})
            self.assertEqual(len(files),len(expected))
            self.assertEqual({'StoryFeedPolicy.kt'}, {Path(path).name for path in module.DIRECT} & expected)
            self.assertTrue({Path(path).name for path in module.DIRECT[1:]}.isdisjoint(expected))
            import v035_story
            raw, record, projection = v035_story.source('StoryFeedPolicy.kt')
            policies = [path for path in files if path.name == 'StoryFeedPolicy.kt']
            self.assertEqual([Path(directory)/projection['output']], policies)
            self.assertEqual(raw.encode('utf-8'), policies[0].read_bytes())
            proof = json.loads((Path(directory)/'original-v035-story-direct-inverse.json').read_text(encoding='utf-8'))
            self.assertTrue(proof['completeRawCopies'])
            self.assertEqual([v035_story.source(name)[1] for name in ('StoryFeedPolicy.kt', 'StoryModels.kt')], proof['sources'])
            self.assertIn(record, proof['sources'])
            for source in (module.DIRECT[0], 'core-data/src/main/java/com/android/purebilibili/data/model/response/StoryModels.kt'):
                self.assertEqual((LIVE/source).read_bytes(), next(p for p in files if p.name==Path(source).name).read_bytes())
            # StoryScreen is a newly complete owner, not an unexplained count increase.
            original=(LIVE/(module.BASE+'feature/story/StoryScreen.kt')).read_text(encoding='utf-8')
            screen=next(path for path in files if path.name=='StoryScreen.kt').read_text(encoding='utf-8')
            self.assertEqual(module.adapt_story_root_screen(original).strip(),screen.split('\n',2)[2].strip())
            helper = module.host(REPO)
            with patch.object(module, 'host', return_value=helper), patch.object(helper, 'write', wraps=helper.write) as write:
                module.generate(REPO, Path(directory))
            # The DIRECT loop must leave this one output to emit_story_models.
            self.assertTrue(write.call_args_list)
            self.assertNotIn(module.DIRECT[0], [call.args[1] for call in write.call_args_list])
    def test_standalone_preserves_all_three_direct_bodies(self):
        with tempfile.TemporaryDirectory() as directory:
            files=module.generate(REPO,Path(directory),True)
            self.assertEqual((LIVE/module.DIRECT[0]).read_bytes(), next(p for p in files if p.name=='StoryFeedPolicy.kt').read_bytes())
            for path in module.DIRECT[1:]:
                output=next(file for file in files if file.name==Path(path).name).read_text(encoding="utf-8")
                self.assertEqual(output.split("\n",2)[2],module.read(REPO,path).strip()+"\n")
    def test_original_topic_mapping_and_defaults_remain(self):
        helpers=module.host(REPO);path=module.ADAPTED[0];original=module.read(REPO,path);body=module.adapt(path,original,helpers)
        self.assertNotIn("NetworkModule",body);self.assertIn("class TopicRepository(private val dynamicApi: DynamicApi)",body)
        self.assertIn("cardList.items.mapNotNull { it.dynamicCardItem }.filter { it.visible }",body)
        self.assertIn("selectedSortBy = sortConfig?.showSortBy ?: sortBy",body)
        self.assertEqual(body.count("if (e is CancellationException) throw e"),2)
        self.assertEqual(body.count("catch (e: Exception)"),original.count("catch (e: Exception)"))
    def test_portrait_policy_only_injects_real_dimension_lookup(self):
        helpers=module.host(REPO);path=module.ADAPTED[1];original=module.read(REPO,path);body=module.adapt(path,original,helpers)
        reversed_body=body.replace("    isVerticalVideo: suspend (String, Long) -> Boolean,\n","")
        reversed_body=reversed_body.replace("                            isVerticalVideo(candidate.bvid, candidate.aid)","                            VideoRepository.isVerticalVideo(\n                                bvid = candidate.bvid,\n                                aid = candidate.aid,\n                            )")
        reversed_body=reversed_body.replace("import com.android.purebilibili.data.model.response.ViewInfo\n","import com.android.purebilibili.data.model.response.ViewInfo\nimport com.android.purebilibili.data.repository.VideoRepository\n")
        self.assertEqual(reversed_body,original)
    def test_original_states_and_topic_merge_selected_verbatim(self):
        with tempfile.TemporaryDirectory() as directory:
            files=module.generate(REPO,Path(directory));helper=module.host(REPO);media=helper.media_extractor(REPO);parser=media.parser_for(REPO)
            state=next(p for p in files if p.name=="TopicDetailStatePolicy.kt").read_text(encoding="utf-8")
            original=module.read(REPO,module.BASE+'feature/search/TopicDetailViewModel.kt');selected=media.function(original,"mergeDynamicItems",parser).replace("private fun","internal fun",1)
            self.assertIn(selected,state);self.assertIn(media.data_class(original,"TopicDetailUiState",parser),state)
    def test_generator_prunes_own_old_direct_preserves_unowned(self):
        helpers=module.host(REPO)
        for path in module.DIRECT:
            with self.subTest(path=path), tempfile.TemporaryDirectory() as directory:
                root=Path(directory);original=module.read(REPO,path)
                target=helpers.write(root,path,original,original)
                module.generate(REPO,root)
                if path == module.DIRECT[0]:
                    # The v035 sole owner replaces the old generated-header copy.
                    self.assertEqual(target.read_bytes(),(LIVE/path).read_bytes())
                else:
                    self.assertFalse(target.exists())
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_text("// user file\n",encoding="utf-8")
                if path == module.DIRECT[0]:
                    with self.assertRaisesRegex(ValueError,"unowned Story model"):
                        module.generate(REPO,root)
                else:
                    module.generate(REPO,root)
                self.assertEqual(target.read_text(),"// user file\n")
    def test_changed_platform_seam_fails_closed(self):
        helpers=module.host(REPO);path=module.ADAPTED[0];original=module.read(REPO,path).replace("object TopicRepository {","object TopicRepository2 {")
        with self.assertRaises(ValueError):module.adapt(path,original,helpers)
    def test_original_topic_link_regex_and_function_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            files=module.generate(REPO,Path(directory));helper=module.host(REPO);media=helper.media_extractor(REPO);parser=media.parser_for(REPO)
            body=next(p for p in files if p.name=="DynamicTopicLinkPolicy.kt").read_text(encoding="utf-8")
            original=module.read(REPO,module.BASE+'feature/dynamic/components/DynamicRichTextPolicy.kt')
            self.assertIn(media.function(original,"resolveDynamicRichTextTopicId",parser),body)
            self.assertIn('"""(?:[?&](?:topic_id|topicId)=)(\\d+)""".toRegex(RegexOption.IGNORE_CASE)',body)

    def test_original_annotated_topic_payload_keyword_fallback_and_action_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            files=module.generate(REPO,Path(directory)); helper=module.host(REPO); media=helper.media_extractor(REPO); parser=media.parser_for(REPO)
            body=next(p for p in files if p.name=="DynamicTopicLinkPolicy.kt").read_text(encoding="utf-8")
            original=module.read(REPO,module.BASE+'feature/dynamic/components/DynamicRichTextPolicy.kt')
            self.assertEqual(body.count("internal sealed interface DynamicRichTextLinkAction {"),1)
            for name in ["resolveDynamicRichTextLinkAction","resolveDynamicRichTextNodeToken"]:
                self.assertIn(media.function(original,name,parser),body)
            self.assertIn(media.function(original,"appendDynamicRichTextTopic",parser).replace("private fun","internal fun",1),body)
            start=original.index("private fun dynamicRichTextLinkAnnotation(")
            end=original.index("\n/** 动态富文本链接动作",start)
            self.assertIn(original[start:end].strip().replace("private fun","internal fun",1),body)
            self.assertEqual(body.count("internal fun dynamicRichTextLinkAnnotation("),1)

if __name__=="__main__":unittest.main()
