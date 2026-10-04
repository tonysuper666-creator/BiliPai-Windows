import hashlib, importlib.util, tempfile, unittest
from pathlib import Path

REPO=next(path for path in Path(__file__).resolve().parents
 if (path/"desktop/tools/extract-upstream-story-topic.py").is_file() and (path/"desktop/upstream-sources.json").is_file())
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
                'StoryScreen.kt','TopicDetailStatePolicy.kt','DynamicTopicLinkPolicy.kt'}
            self.assertEqual(expected,{path.name for path in files})
            self.assertEqual(len(files),len(expected))
            self.assertTrue({Path(path).name for path in module.DIRECT}.isdisjoint(expected))
            # StoryScreen is a newly complete owner, not an unexplained count increase.
            original=module.read(REPO,module.BASE+'feature/story/StoryScreen.kt')
            screen=next(path for path in files if path.name=='StoryScreen.kt').read_text(encoding='utf-8')
            self.assertEqual(module.adapt_story_root_screen(original).strip(),screen.split('\n',2)[2].strip())
    def test_standalone_preserves_all_three_direct_bodies(self):
        with tempfile.TemporaryDirectory() as directory:
            files=module.generate(REPO,Path(directory),True)
            for path in module.DIRECT:
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
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);path=module.DIRECT[0];original=module.read(REPO,path)
            target=helpers.write(root,path,original,original);module.generate(REPO,root);self.assertFalse(target.exists())
            target.parent.mkdir(parents=True,exist_ok=True);target.write_text("// user file\n",encoding="utf-8")
            module.generate(REPO,root);self.assertEqual(target.read_text(),"// user file\n")
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
