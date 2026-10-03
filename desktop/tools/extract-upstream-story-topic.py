"""Original Story/Topic models and policies; Windows transport injection only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re

BASE = "app/src/main/java/com/android/purebilibili/"
DIRECT = [BASE + "feature/story/StoryFeedPolicy.kt", BASE + "feature/search/TopicDetailVisualPolicy.kt",
          BASE + "navigation/PortraitStoryNavigationPolicy.kt"]
ADAPTED = [BASE + "data/repository/TopicRepository.kt", BASE + "feature/video/ui/pager/PortraitPagerSwitchPolicy.kt"]
EXTRACT = [BASE + "feature/story/StoryViewModel.kt", BASE + "feature/story/StoryScreen.kt", BASE + "feature/search/TopicDetailViewModel.kt",
           BASE + "feature/dynamic/components/DynamicRichTextPolicy.kt"]

def module(repo, name, path):
    spec = importlib.util.spec_from_file_location(name, repo / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value

def host(repo): return module(repo, "story_topic_host", "desktop/tools/extract-upstream-plugins.py")
def read(repo, path): return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").replace("\r\n", "\n")

def adapt(path, source, helpers):
    if path.endswith("TopicRepository.kt"):
        source = helpers.substitute(source, "import com.android.purebilibili.core.network.NetworkModule", "import com.android.purebilibili.core.network.DynamicApi\nimport kotlinx.coroutines.CancellationException")
        source = helpers.substitute(source, "object TopicRepository {", "class TopicRepository(private val dynamicApi: DynamicApi) {")
        source = helpers.substitute(source, "NetworkModule.dynamicApi", "dynamicApi", count=2)
        source = helpers.substitute(source, "        } catch (e: Exception) {\n            e.printStackTrace()", "        } catch (e: Exception) {\n            if (e is CancellationException) throw e", count=2)
    else:
        source = helpers.substitute(source, "import com.android.purebilibili.data.repository.VideoRepository\n", "")
        source = helpers.substitute(source, "    enabled: Boolean,\n): List<RelatedVideo> {", "    enabled: Boolean,\n    isVerticalVideo: suspend (String, Long) -> Boolean,\n): List<RelatedVideo> {")
        source = helpers.substitute(source, "                            VideoRepository.isVerticalVideo(\n                                bvid = candidate.bvid,\n                                aid = candidate.aid,\n                            )", "                            isVerticalVideo(candidate.bvid, candidate.aid)")
    return source


def adapt_story_root_vm(source):
 body=source[source.index("class StoryViewModel("):]
 before='class StoryViewModel(application: Application) : AndroidViewModel(application) {'
 after='internal class StoryViewModel(private val environment: com.bilipai.desktop.ui.DesktopOriginalStoryEnvironment) {\n    private val viewModelScope get() = environment.scope\n    private val VideoRepository get() = environment.requests\n    private fun <T> MutableStateFlow(initial:T): MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)'
 assert body.count(before)==1
 body=body.replace(before,after)
 before='val result = VideoRepository.getHomeVideos('
 after='environment.assertCurrent()\n            val result = VideoRepository.getHomeVideos('
 assert body.count(before)==2
 body=body.replace(before,after)
 before='            result.onSuccess'
 after='            environment.assertCurrent()\n            result.onSuccess'
 assert body.count(before)==2
 body=body.replace(before,after)
 return body

def adapt_story_root_screen(source):
 before='import androidx.compose.ui.platform.LocalContext\n'
 after=''
 assert source.count(before)==1
 source=source.replace(before,after)
 before='import androidx.lifecycle.viewmodel.compose.viewModel\n'
 after=''
 assert source.count(before)==1
 source=source.replace(before,after)
 before='import androidx.media3.common.util.UnstableApi\n'
 after=''
 assert source.count(before)==1
 source=source.replace(before,after)
 before='@UnstableApi\n'
 after=''
 assert source.count(before)==1
 source=source.replace(before,after)
 before='fun StoryScreen('
 after='internal fun StoryScreen('
 assert source.count(before)==1
 source=source.replace(before,after)
 before='viewModel: StoryViewModel = viewModel(),'
 after='viewModel: StoryViewModel,'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='playerViewModel: VideoPlaybackViewModel = viewModel(),'
 after='playerViewModel: VideoPlaybackViewModel,'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='engagementViewModel: VideoEngagementViewModel = viewModel(),'
 after='engagementViewModel: VideoEngagementViewModel,'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='onVideoClick: (String, Long, String) -> Unit = { _, _, _ -> },'
 after='onVideoClick: (String, Long, String) -> Unit,'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='onUserClick: (Long) -> Unit = {},'
 after='onUserClick: (Long) -> Unit,'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='onSearchClick: () -> Unit = {},'
 after='onSearchClick: () -> Unit,'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='onRotateToLandscape: () -> Unit = {}'
 after='onRotateToLandscape: () -> Unit'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='    val context = LocalContext.current'
 after='    val platform = com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform.current\n    val context = platform.section.settingsContext'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='com.android.purebilibili.core.store.SettingsManager'
 after='com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='engagementViewModel.initWithContext(context)'
 after='engagementViewModel.initWithContext(context.pluginContext)'
 assert source.count(before)==1
 source=source.replace(before,after)
 before='viewModel = playerViewModel,'
 after='viewModel = com.bilipai.desktop.ui.LocalDesktopOriginalStoryPlaybackView.current,'
 assert source.count(before)==1
 source=source.replace(before,after)
 return source

def generate(repo, output, standalone=False):
    helpers = host(repo); media = helpers.media_extractor(repo); parser = media.parser_for(repo)
    output.mkdir(parents=True, exist_ok=True); result = []
    for path in DIRECT:
        source = read(repo, path); helpers.prune_old_direct(output, path, source)
        if standalone: result.append(helpers.write(output, path, source, source))
    for path in ADAPTED:
        source = read(repo, path); result.append(helpers.write(output, path, source, adapt(path, source, helpers)))
    for path in EXTRACT:
        source = read(repo, path)
        if path.endswith("StoryViewModel.kt"):
            body = "package com.android.purebilibili.feature.story\nimport com.android.purebilibili.data.model.response.StoryItem\nimport android.util.Log as Logger\nimport kotlinx.coroutines.flow.*\nimport kotlinx.coroutines.launch\n\n" + media.data_class(source, "StoryUiState", parser) + "\n\n" + adapt_story_root_vm(source)
            name = "StoryUiState.kt"
        elif path.endswith("StoryScreen.kt"):
            body = adapt_story_root_screen(source)
            name = "StoryScreen.kt"
        elif path.endswith("TopicDetailViewModel.kt"):
            body = "package com.android.purebilibili.feature.search\nimport com.android.purebilibili.data.model.response.*\n\n" + media.data_class(source, "TopicDetailUiState", parser) + "\n\n" + media.function(source, "mergeDynamicItems", parser).replace("private fun", "internal fun", 1)
            name = "TopicDetailStatePolicy.kt"
        else:
            patterns = re.findall(r'(?ms)^private val DYNAMIC_TOPIC_QUERY_ID_PATTERN =\n.*?(?=\n\nprivate fun AnnotatedString.Builder.appendDynamicRichTextTopic)', source)
            if len(patterns) != 1: raise ValueError("Original topic link regex policies changed")
            body = "package com.android.purebilibili.feature.dynamic.components\nimport com.android.purebilibili.data.model.response.RichTextNode\n\n" + media.function(source, "resolveDynamicRichTextTopicId", parser) + "\n\n" + patterns[0]
            constants = re.findall(r'^internal const val DYNAMIC_RICH_TEXT_LINK_\w+ = .*$', source, re.MULTILINE)
            if len(constants) != 6: raise ValueError("Original rich text payload prefixes changed")
            start = source.index("internal sealed interface DynamicRichTextLinkAction {")
            end = source.index("\n}\n", start) + 2
            body += "\n\n" + "\n".join(constants) + "\n\n" + source[start:end]
            body += "\n\n" + media.function(source, "resolveDynamicRichTextLinkAction", parser)
            body += "\n\n" + media.function(source, "resolveDynamicRichTextNodeToken", parser)
            annotation_start = source.index("private fun dynamicRichTextLinkAnnotation(")
            annotation_end = source.index("\n/** 动态富文本链接动作", annotation_start)
            body += "\n\n" + source[annotation_start:annotation_end].strip().replace("private fun", "internal fun", 1)
            body += "\n\n" + media.function(source, "appendDynamicRichTextTopic", parser).replace("private fun", "internal fun", 1)
            body = body.replace("import com.android.purebilibili.data.model.response.RichTextNode\n",
                "import com.android.purebilibili.data.model.response.RichTextNode\n" +
                "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.text.*\nimport androidx.compose.ui.text.font.FontWeight\n")
            name = "DynamicTopicLinkPolicy.kt"
        result.append(helpers.write(output, path, source, body, name))
    return result

def inventory(repo):
    return [dict(path=path, mode="direct" if path in DIRECT else "platform-adapter-reference" if path in ADAPTED else "policy-extract",
        features=["story-topic"], sha256=hashlib.sha256(read(repo, path).encode()).hexdigest()) for path in DIRECT + ADAPTED + EXTRACT]

if __name__ == "__main__":
    cli=argparse.ArgumentParser(description=__doc__); cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path); cli.add_argument("--standalone", action="store_true"); cli.add_argument("--inventory", action="store_true")
    args=cli.parse_args(); repo=args.repo.resolve()
    if args.inventory: print(json.dumps(inventory(repo), indent=2))
    if args.output: print("Generated", len(generate(repo, args.output.resolve(), args.standalone)), "original Story/Topic files")
