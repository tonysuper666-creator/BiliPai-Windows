"""Complete fixed beta2 ReactionSheet UI, adapted to the existing account/page State."""
from pathlib import Path
import hashlib
COMMIT = "b2091b1de636e499df80af7e700796b4f52b7f92"
BASE = "desktop/third-party/dynamic-reaction-v034/"
PINS = {
    "DynamicCommentSheet.kt": ("1c96ba707d4c4c8fae2600aa24036abd7b3a6cf712a25c0e0e87bea4a1b0cf81", "7e7ce0de9868f3c4915f9c96d1ff33452e502829"),
    "DynamicReactionSheet.kt": ("43952cbeef69e4289659ecb5a6c9be746024c6aa8632d082e5fee6e44507d11f", "7d64674ba5c242ce7ea7a5ecdac646a87c33a92a"),
}
def source(repo, name):
    raw = (Path(repo) / BASE / name).read_bytes()
    digest, blob = PINS[name]
    actual_blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    if hashlib.sha256(raw).hexdigest() != digest or actual_blob != blob:
        raise ValueError("Fixed beta2 DynamicReaction UI source drift: " + name)
    return raw.decode("utf8").replace("\r\n", "\n")
def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError("Original complete ReactionSheet seam changed: " + before[:80])
    return text.replace(before, after, 1)
def sheet_body(original):
    text = original
    text = replace_once(text, "import com.android.purebilibili.core.network.grpc.DynamicReactionApi", "import com.bilipai.desktop.data.DesktopDynamicReactionListState")
    text = text.replace("import com.android.purebilibili.core.network.grpc.DynamicReactionUser\n", "")
    text = text.replace("import com.android.purebilibili.data.model.response.DynamicItem\n", "")
    text = text.replace("import kotlinx.coroutines.CancellationException\n", "")
    text = replace_once(text, "fun DynamicReactionSheet(item: DynamicItem, onDismiss: () -> Unit, onUserClick: (Long) -> Unit)",
        "internal fun DesktopOriginalDynamicReactionSheet(list: DesktopDynamicReactionListState, onDismiss: () -> Unit, onUserClick: (Long) -> Unit)")
    begin = text.index("    var users by remember(item.id_str)")
    end = text.index("    AppModalBottomSheet(onDismissRequest = onDismiss)", begin)
    text = text[:begin] + """    val snapshot by list.state.collectAsState()
    val users = snapshot.users
    val hasMore = snapshot.hasMore
    val loading = snapshot.loading
    val loaded = snapshot.loaded
    val error = snapshot.error
    val title = snapshot.title
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    DisposableEffect(list) { onDispose { list.close() } }
    if (!list.isOwned()) return
    suspend fun load() { list.load() }
    LaunchedEffect(list) { load() }
    LaunchedEffect(list, listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            // Original cursor/four-row pagination policy is owned by this same State.
            snapshot.offset to list.shouldLoadMore(lastVisible, layout.totalItemsCount)
        }.collect { (_, shouldLoad) ->
            if (shouldLoad) load()
        }
    }
""" + text[end:]
    text = replace_once(text, "AppModalBottomSheet(onDismissRequest = onDismiss)",
        "AppModalBottomSheet(onDismissRequest = { if (list.isOwned()) onDismiss() })")
    text = replace_once(text, ".clickable { onDismiss(); onUserClick(user.mid) }",
        ".clickable { if (list.isOwned()) onUserClick(user.mid) }")
    text = replace_once(text, "AppTextButton(onClick = { scope.launch { load() } })",
        "AppTextButton(onClick = { if (list.isOwned()) scope.launch { load() } })")
    return text
