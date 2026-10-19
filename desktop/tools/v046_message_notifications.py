"""Original v0.4.6 message-notification accuracy on the existing Windows owners.

Re-pins v038-message-notifications to the v0.4.6 blobs. The existing notification producer stays
the sole owner: Policy, Poller, state store and DynAllPersonal preload are emitted as complete
original files with hash-pinned platform adaptations, the recent-author red-dot snapshot
(portal and uplist, author-level has_update) is emitted beside them, and the original tests are
carried as JVM tests. The read_offset watermark no longer exists upstream; nothing here
reintroduces it.

The snapshot endpoints need no App access token, but the original Poller calls them only after the
token-gated following-list read and DynAllPersonal preload. A web-only account (no TV authorization)
therefore gets no dynamic notifications at all; the snapshot adds nothing observable for it.
"""
from pathlib import Path
import hashlib
import json

from v038_message_notifications import adapt as v038_adapt, COMMIT as V038_COMMIT, MANIFEST_SHA256 as V038_MANIFEST_SHA256

COMMIT = "d6bf8b1655ae910999860bf13e65c8fcea5b345b"
ARCHIVE = "desktop/upstream-slices/v046-message-notifications"
MANIFEST_SHA256 = "11ebdc367077337c6e42542b8878b6e2157d289b839c137fbaa8d46701f4a476"
EXPECTED = {
    "MessageNotificationChecker.kt", "MessageNotificationPolicy.kt", "MessageNotificationStateStore.kt",
    "MessageNotificationPolicyTest.kt", "DynamicPersonalFeedApi.kt", "DynamicPersonalFeedApiTest.kt",
    "DynamicAuthorUpdatesApi.kt", "DynamicAuthorUpdatesApiTest.kt", "DynamicModels.kt",
}
TEST_CASES = {"MessageNotificationPolicyTest.kt": 45, "DynamicPersonalFeedApiTest.kt": 9,
              "DynamicAuthorUpdatesApiTest.kt": 13}

# The two DynamicApi members live in ApiClient.kt, which this tree already carries byte for byte at
# the same commit in the favorite-editor archive. They are read from there, never copied again.
API_ARCHIVE = "desktop/upstream-slices/v046-favorite-editor"
API_FILE = "ApiClient.kt"
API_BYTES = 155454
API_SHA256 = "8a1e75f05835c62d4cfe2827855068653e117198d0bf344d85881853b4a8ed9d"
API_BLOB = "55a9728773945c749c95245064f1ad36d65919c6"
API_BEGIN = "    // Recent authors, not the complete followed-author list. Reading these flags is read-only.\n"
API_END = "    ): DynamicAuthorUpdatesPageResponse\n"
API_SELECTED_SHA256 = "5f44a8048771b52716996769db11824b6bcd7dd760add1fbfb39729d5584640d"

DTO_BEGIN = "// Read-only recent-author flags. Missing flags remain unknown, unlike the legacy integer uplist.\n"
DTO_END = '@SerialName("has_update") val hasUpdate: Boolean? = null\n)\n'
DTO_SELECTED_SHA256 = "fccd7306fe2c3cb842f50c17b48d9d8f0c2f81dbecadc18214a78615d1255d75"

# The App-authorization gate of DynAllPersonal. The original check message is kept word for word.
APP_AUTHORIZATION_REASON = "动态预加载需要 App 登录授权，网页 Cookie 不能替代"
ASSERT_TRUE_BEFORE = ('        assertTrue("An incomplete or inconsistent response must be rejected",\n'
                      '            failure is IOException || failure is IllegalStateException)')
# JUnit 4 puts the message first, kotlin.test puts it last. Same condition, same message.
ASSERT_TRUE_AFTER = ('        assertTrue(failure is IOException || failure is IllegalStateException,\n'
                     '            "An incomplete or inconsistent response must be rejected")')

SERVICE_HEAD = (
    "package com.android.purebilibili.core.network\n\n"
    "import com.android.purebilibili.data.model.response.DynamicAuthorUpdatesPageResponse\n"
    "import com.android.purebilibili.data.model.response.DynamicAuthorUpdatesPortalResponse\n"
    "import retrofit2.http.GET\n"
    "import retrofit2.http.Query\n\n"
    "/** The two v0.4.6 DynamicApi members for the recent-author red dots, declared verbatim as their own\n"
    " * service so the shared DynamicApi producer chain stays untouched. Read-only web endpoints that use\n"
    " * the existing account cookies; the endpoints themselves need no App access token. The original Poller\n"
    " * only reaches them after the following-list read and DynAllPersonal preload, which do need that token,\n"
    " * so an account without one never gets here and receives no dynamic notifications at all. */\n"
    "interface DesktopDynamicAuthorUpdatesService {\n")
SERVICE_TAIL = "}\n"
MODELS_HEAD = (
    "package com.android.purebilibili.data.model.response\n\n"
    "import kotlinx.serialization.SerialName\n"
    "import kotlinx.serialization.Serializable\n\n"
    "// Whole v0.4.6 declarations from DynamicModels.kt. The Windows tree still emits the v0.3.5 file; when it\n"
    "// advances to the whole v0.4.6 file this emission must be retired, or the names are declared twice.\n")

MAIN = {
    "policy": "com/android/purebilibili/feature/message/notification/MessageNotificationPolicy.kt",
    "checker": "com/android/purebilibili/feature/message/notification/MessageNotificationChecker.kt",
    "state": "com/android/purebilibili/feature/message/notification/MessageNotificationStateStore.kt",
    "personal": "com/android/purebilibili/core/network/grpc/DesktopDynamicPersonalFeedApi.kt",
    "authors": "com/android/purebilibili/core/network/DesktopDynamicAuthorUpdatesApi.kt",
    "service": "com/android/purebilibili/core/network/DesktopDynamicAuthorUpdatesService.kt",
    "models": "com/android/purebilibili/data/model/response/DesktopDynamicAuthorUpdatesModels.kt",
}
TESTS = {
    "MessageNotificationPolicyTest.kt": "policy-tests/com/android/purebilibili/feature/message/notification/MessageNotificationPolicyTest.kt",
    "DynamicPersonalFeedApiTest.kt": "policy-tests/com/android/purebilibili/core/network/grpc/DynamicPersonalFeedApiTest.kt",
    "DynamicAuthorUpdatesApiTest.kt": "policy-tests/com/android/purebilibili/core/network/DynamicAuthorUpdatesApiTest.kt",
}


def need(condition, message):
    if not condition:
        raise ValueError(message)


def sha(value):
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else value).hexdigest()


def blob(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), "Missing v046 notification inputs")
    need({p.name for p in root.iterdir()} == EXPECTED | {"manifest.json", "LICENSE"}, "Unexpected v046 notification archive file set")
    raw = (root / "manifest.json").read_bytes()
    need(sha(raw) == MANIFEST_SHA256, "Fixed v046 notification manifest changed")
    manifest = json.loads(raw)
    need((manifest["repository"], manifest["tag"], manifest["commit"], manifest["hashNormalization"]) ==
         ("jay3-yy/BiliPai", "v0.4.6", COMMIT, "raw"), "Unknown v046 notification source identity")
    need(manifest["replaces"]["manifestSha256"] == V038_MANIFEST_SHA256 and manifest["replaces"]["commit"] == V038_COMMIT,
         "The re-pinned v038 notification archive identity changed")
    need(len(manifest["files"]) == len(EXPECTED) and {r["filename"] for r in manifest["files"]} == EXPECTED,
         "Unexpected v046 notification source set")
    sources = {}
    for row in manifest["files"]:
        file = root / row["filename"]
        need(file.is_file() and not file.is_symlink() and Path(row["path"]).name == file.name,
             "Linked or ambiguous v046 notification input")
        data = file.read_bytes()
        need(len(data) == row["bytes"] and sha(data) == row["sha256"], "v046 notification source bytes changed: " + file.name)
        need(blob(data) == row["gitBlob"], "v046 notification Git blob changed: " + file.name)
        sources[file.name] = data.replace(b"\r\n", b"\n").decode("utf-8")
    licence = root / "LICENSE"
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == manifest["license"]["sha256"],
         "v046 notification license changed")
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    roots = [(Path(repo) / ARCHIVE).resolve(), (Path(repo) / API_ARCHIVE).resolve()]
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        for root in roots:
            need(not (target.is_relative_to(root) or root.is_relative_to(target)),
                 "Output overlaps original v046 notification inputs")


def adapt(original, mappings):
    """The inverse-checked adaptation of v038, with its receipt stamped for the commit these bytes come from.

    The shared helper hard-codes its own v0.3.8 commit into every proof, which is wrong for a v0.4.6 body.
    Replacing the value keeps the key at its position, so the receipt layout is unchanged."""
    body, proof = v038_adapt(original, mappings)
    return body, dict(proof, upstreamCommit=COMMIT)


def select(text, begin, end, label):
    need(text.count(begin) == 1 and text.count(end) == 1, "v046 notification selection anchor changed: " + label)
    start = text.index(begin)
    stop = text.index(end) + len(end)
    need(start < stop, "v046 notification selection anchors are out of order: " + label)
    return text[start:stop]


def api_declarations(repo):
    """The two DynamicApi members, verified against the pinned ApiClient.kt of the same upstream commit."""
    root = Path(repo) / API_ARCHIVE
    file = root / API_FILE
    need(root.is_dir() and not root.is_symlink() and file.is_file() and not file.is_symlink(),
         "Missing pinned ApiClient.kt for the recent-author declarations")
    data = file.read_bytes()
    need(len(data) == API_BYTES and sha(data) == API_SHA256 and blob(data) == API_BLOB,
         "Pinned ApiClient.kt changed; re-pin the recent-author declarations")
    selected = select(data.decode("utf-8"), API_BEGIN, API_END, "ApiClient recent-author members")
    need(sha(selected) == API_SELECTED_SHA256, "Recent-author API declarations changed")
    return selected


def dynamic_models_block(repo):
    sources, _ = fixed(repo)
    selected = select(sources["DynamicModels.kt"], DTO_BEGIN, DTO_END, "DynamicModels recent-author DTOs")
    need(sha(selected) == DTO_SELECTED_SHA256, "Recent-author DTO declarations changed")
    return selected


def pure_policy(repo):
    sources, _ = fixed(repo)
    return adapt(sources["MessageNotificationPolicy.kt"], [])


def notification_poller(repo):
    sources, _ = fixed(repo)
    original = sources["MessageNotificationChecker.kt"]
    network_start = original.index("private object NetworkMessageNotificationSource")
    network_end = original.index("internal data class MessageNotificationCheckResult", network_start)
    wrapper_start = original.index("internal class MessageNotificationChecker(")
    mappings = [(original[network_start:network_end], ""), (original[wrapper_start:], "")]
    for line in [
        "import android.content.Context\n",
        "import com.android.purebilibili.core.network.DynamicAuthorUpdatesApi\n",
        "import com.android.purebilibili.core.network.NetworkModule\n",
        "import com.android.purebilibili.core.store.TokenManager\n",
        "import com.android.purebilibili.core.network.grpc.DynamicPersonalFeedApi\n",
        "import com.android.purebilibili.data.repository.MessageRepository\n",
        "import kotlinx.coroutines.flow.first\n",
        "import kotlinx.coroutines.sync.withLock\n",
    ]:
        mappings.append((line, ""))
    # The source interface (now including dynamicAuthorUpdates), result, helpers and the complete
    # Poller survive. Android network/checker wrappers are supplied by the retained Windows Root.
    return adapt(original, mappings)


def state_store(repo):
    sources, _ = fixed(repo)
    return adapt(sources["MessageNotificationStateStore.kt"], [
        ("import android.content.Context", "import com.bilipai.desktop.ui.DesktopMessageNotificationContext as Context"),
        ("import androidx.datastore.preferences.core.edit\n", ""),
        ("import androidx.datastore.preferences.core.stringPreferencesKey\n", ""),
        ("import androidx.datastore.preferences.preferencesDataStore\n", ""),
        ("import kotlinx.coroutines.flow.first\n", "import com.bilipai.desktop.plugins.stringPreferencesKey\nimport kotlinx.coroutines.flow.first\n"),
        ('private val Context.messageNotificationStateStore by preferencesDataStore(name = "message_notification_state")',
         "private val Context.messageNotificationStateStore get() = state"),
    ])


def personal_feed(repo):
    sources, _ = fixed(repo)
    return adapt(sources["DynamicPersonalFeedApi.kt"], [
        ("import com.android.purebilibili.core.store.TokenManager\n", ""),
        ("object DynamicPersonalFeedApi {",
         "internal class DesktopDynamicPersonalFeedApi(private val request: suspend (String, ByteArray) -> ByteArray,\n"
         "    private val accessToken: () -> String?) {"),
        ('    private const val PATH = "/bilibili.app.dynamic.v2.Dynamic/DynAllPersonal"',
         '    private val PATH = "/bilibili.app.dynamic.v2.Dynamic/DynAllPersonal"'),
        ("check(!TokenManager.accessTokenCache.isNullOrBlank())", "check(!accessToken().isNullOrBlank())"),
        ("BiliGrpcClient.request(PATH, buildDynamicPersonalFeedRequest(hostUid, offset, page))",
         "request(PATH, buildDynamicPersonalFeedRequest(hostUid, offset, page))"),
    ])


def author_updates_api(repo):
    sources, _ = fixed(repo)
    return adapt(sources["DynamicAuthorUpdatesApi.kt"], [
        ("object DynamicAuthorUpdatesApi {",
         "internal class DesktopDynamicAuthorUpdatesApi(private val service: DesktopDynamicAuthorUpdatesService) {"),
        ("        loadPortal = { NetworkModule.dynamicApi.getDynamicAuthorUpdatesPortal() },",
         "        loadPortal = { service.getDynamicAuthorUpdatesPortal() },"),
        ("        loadPage = { offset -> NetworkModule.dynamicApi.getDynamicAuthorUpdatesPage(offset) }",
         "        loadPage = { offset -> service.getDynamicAuthorUpdatesPage(offset) }"),
    ])


def author_updates_service(repo):
    selected = api_declarations(repo)
    body = SERVICE_HEAD + selected + SERVICE_TAIL
    return body, dict(upstreamCommit=COMMIT, source=API_ARCHIVE + "/" + API_FILE, sourceGitBlob=API_BLOB,
                      selectedSha256LF=sha(selected), generatedSha256LF=sha(body), wholeSelectionVerbatim=True,
                      rootMounted=False, mainCompiled=False)


def author_updates_models(repo):
    selected = dynamic_models_block(repo)
    body = MODELS_HEAD + selected
    return body, dict(upstreamCommit=COMMIT, source=ARCHIVE + "/DynamicModels.kt",
                      selectedSha256LF=sha(selected), generatedSha256LF=sha(body), wholeSelectionVerbatim=True,
                      rootMounted=False, mainCompiled=False)


def original_tests(repo):
    sources, _ = fixed(repo)
    result = {}
    for name in TEST_CASES:
        mappings = []
        for line in sources[name].splitlines(keepends=True):
            if line.startswith("import org.junit.Assert."):
                assertion = line.removeprefix("import org.junit.Assert.").strip()
                need(assertion in {"assertEquals", "assertFalse", "assertNull", "assertTrue", "fail"},
                     "Unknown original JUnit assertion import")
                mappings.append((line, "import kotlin.test." + assertion + "\n"))
            elif line == "import org.junit.Test\n":
                mappings.append((line, "import kotlin.test.Test\n"))
        if name == "DynamicAuthorUpdatesApiTest.kt":
            mappings.append((ASSERT_TRUE_BEFORE, ASSERT_TRUE_AFTER))
        body, proof = adapt(sources[name], mappings)
        need(body.count("@Test") == sources[name].count("@Test") == TEST_CASES[name],
             "Original notification test count changed: " + name)
        need("org.junit" not in body, "JUnit 4 reference remains in " + name)
        result[name] = (body, proof)
    return result


def build(repo):
    """Every output body and proof, computed and validated before anything is written."""
    main = {}
    for key, fn in [("policy", pure_policy), ("checker", notification_poller), ("state", state_store),
                    ("personal", personal_feed), ("authors", author_updates_api),
                    ("service", author_updates_service), ("models", author_updates_models)]:
        main[key] = fn(repo)
    return main, original_tests(repo)


def generate(repo, output):
    output = Path(output)
    validate_outputs(repo, output)
    main, tests = build(repo)
    files, proofs = [], []
    for key, relative in MAIN.items():
        body, proof = main[key]
        dest = output / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(body.encode("utf-8"))
        files.append(relative)
        proofs.append(dict(path=relative, **proof))
    for name, relative in TESTS.items():
        body, proof = tests[name]
        dest = output / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(body.encode("utf-8"))
        proofs.append(dict(path=relative, originalTestCases=body.count("@Test"), **proof))
    receipt = dict(upstreamCommit=COMMIT, sourceManifestSha256=MANIFEST_SHA256,
                   replacesV038ManifestSha256=V038_MANIFEST_SHA256,
                   appAuthorizationReason=APP_AUTHORIZATION_REASON,
                   dynamicPreloadWithoutAppToken="explicit failure before any request; no baseline is committed",
                   readOffsetWatermarkReintroduced=False, jvmTestsExecuted=False, files=proofs)
    (output / "v046-message-notification-source-proof.json").write_bytes(
        (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return files
