"""Original Story/Topic models and policies; Windows transport injection only."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, re

BASE = "app/src/main/java/com/android/purebilibili/"
DIRECT = [BASE + "feature/story/StoryFeedPolicy.kt", BASE + "feature/search/TopicDetailVisualPolicy.kt",
          BASE + "navigation/PortraitStoryNavigationPolicy.kt"]
ADAPTED = [BASE + "data/repository/TopicRepository.kt", BASE + "feature/video/ui/pager/PortraitPagerSwitchPolicy.kt"]
EXTRACT = [BASE + "feature/story/StoryViewModel.kt", BASE + "feature/search/TopicDetailViewModel.kt",
           BASE + "feature/dynamic/components/DynamicRichTextPolicy.kt"]

def module(repo, name, path):
    spec = importlib.util.spec_from_file_location(name, repo / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value

def host(repo): return module(repo, "story_topic_host", "desktop/tools/extract-upstream-plugins.py")
def read(repo, path): return (repo / path).read_text(encoding="utf-8").replace("\r\n", "\n")

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
            body = "package com.android.purebilibili.feature.story\nimport com.android.purebilibili.data.model.response.StoryItem\n\n" + media.data_class(source, "StoryUiState", parser)
            name = "StoryUiState.kt"
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
