"""Fixed complete v038 notification bodies; existing producers remain sole owners."""
from pathlib import Path
import hashlib
import json

COMMIT = "b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14"
ARCHIVE = Path("desktop/upstream-slices/v038-message-notifications")
MANIFEST_SHA256 = "bf8f62448f5293bec2876476698ee5acceb9b7781ad245f73162c2490455f1a9"
PINS = {
    "MessageNotificationChecker.kt": [
        "9dc20551f97ea39332247f9b651f53035952632cfd0caf0b880c24784b9f5991",
        22662,
        "511fc0ea0129b67b082333e2734f9731272fd5f1"
    ],
    "MessageNotificationPolicy.kt": [
        "1c6285307006b272dddd2fac14ba7c60142b77e7b00b50d268be5213aa3c9889",
        6679,
        "c731f02aa2a1e5b95f9196b4b96493828200dda5"
    ],
    "MessageNotificationStateStore.kt": [
        "302bb36a1a7260a74a590899b43dbdf59a958c3401f9a8ea42dd35e00a732357",
        2973,
        "a2fafaf2793b4e571d64306d8cedfa886c2e7047"
    ],
    "MessageNotificationPolicyTest.kt": [
        "e4c74995bfc6d4de81d0a787a2d0bf10a03a1ede2a797548809cdeac66536d95",
        39073,
        "78345ec02d165e84f7ed31d15f58dfb13150b6ef"
    ],
    "MessageNotificationSettingsStore.kt": [
        "b54bfb95eed322989cbed9544b0e442535a3a81586ada4bb4fd454ae43e0a54d",
        5665,
        "ccc50d07cf93148bc4f5810ad96194cdc1f05d2c"
    ],
    "DynamicPersonalFeedApi.kt": [
        "e24f626ab2b47e121a3d5da9e26bd76d9f61af4957c0abf54a9b8de7072e8387",
        13470,
        "4a0f656b596850b5defb9da799b82572bfc807a5"
    ],
    "DynamicPersonalFeedApiTest.kt": [
        "b2f936ddc5a37b5ee6bd4dffde9994ec2f369e017af2027ce9c0f63e886e2751",
        7570,
        "b8c6e3c4cae32bfb8bf0c3541710530b38e2ab92"
    ],
    "BiliGrpcClient.kt": [
        "18e5ab7fae60cb0b62a0309076aeedd521d460e563d405676064e23f0b60ca01",
        6377,
        "e6d49d15e9962926269b7daf2cdcd57ca7204617"
    ],
    "ProtoWire.kt": [
        "5c44c12bc3db870874a4e1ea9e01f39faf03425f3db2c95576c4bded93fab31e",
        12148,
        "01947331d4efafcdabf23afdb18e6118f082aaee"
    ]
}


def sha(raw):
    return hashlib.sha256(raw.encode("utf-8") if isinstance(raw, str) else raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    if root.is_symlink() or (root / "manifest.json").is_symlink():
        raise ValueError("Linked notification source rejected")
    raw = (root / "manifest.json").read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError("Fixed notification manifest changed")
    manifest = json.loads(raw)
    if manifest["schemaVersion"] != 1 or manifest["fixedUpstreamCommit"] != COMMIT:
        raise ValueError("Unknown notification source identity")
    if {p.name for p in root.iterdir()} != set(PINS) | {"manifest.json"}:
        raise ValueError("Unexpected notification archive file set")
    rows = {r["filename"]: r for r in manifest["files"]}
    if len(rows) != len(manifest["files"]) or set(rows) != set(PINS):
        raise ValueError("Unexpected notification source set")
    result = {}
    for name, (digest, size, blob) in PINS.items():
        path = root / name
        if path.is_symlink():
            raise ValueError("Linked notification input rejected")
        raw = path.read_bytes()
        row = rows[name]
        if (row["sha256Bytes"], row["bytes"], row["gitBlob"]) != (digest, size, blob):
            raise ValueError("Notification input pin changed")
        actual_blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        if sha(raw) != digest or len(raw) != size or actual_blob != blob:
            raise ValueError("Notification source bytes changed")
        result[name] = raw.replace(b"\r\n", b"\n").decode("utf-8")
    return result, manifest


def adapt(original, mappings):
    text = original
    edits = []
    for before, after in mappings:
        if text.count(before) != 1:
            raise ValueError("Notification platform anchor changed: " + before[:80])
        # An empty platform deletion is recovered by its exact byte/character
        # insertion position, preserving the whole original selected source.
        offset = text.index(before)
        text = text[:offset] + after + text[offset + len(before):]
        edits.append(dict(before=before, after=after, offset=offset))
    inverse = text
    for edit in reversed(edits):
        offset, after = edit["offset"], edit["after"]
        if inverse[offset:offset+len(after)] != after:
            raise ValueError("Notification inverse anchor changed")
        inverse = inverse[:offset] + edit["before"] + inverse[offset+len(after):]
    if inverse != original:
        raise ValueError("Complete notification source inverse differs")
    return text, dict(upstreamCommit=COMMIT, originalSha256LF=sha(original),
                      generatedSha256LF=sha(text), edits=edits,
                      completeOriginalInverse=True, rootMounted=False, mainCompiled=False)


def pure_policy(repo):
    sources, _ = fixed_sources(repo)
    return adapt(sources["MessageNotificationPolicy.kt"], [])


def notification_poller(repo):
    sources, _ = fixed_sources(repo)
    original = sources["MessageNotificationChecker.kt"]
    network_start = original.index("private object NetworkMessageNotificationSource")
    network_end = original.index("internal data class MessageNotificationCheckResult", network_start)
    wrapper_start = original.index("internal class MessageNotificationChecker(")
    mappings = [(original[network_start:network_end], ""),
                (original[wrapper_start:], "")]
    for line in [
        "import android.content.Context\n",
        "import com.android.purebilibili.core.network.NetworkModule\n",
        "import com.android.purebilibili.core.store.TokenManager\n",
        "import com.android.purebilibili.core.network.grpc.DynamicPersonalFeedApi\n",
        "import com.android.purebilibili.data.repository.MessageRepository\n",
        "import kotlinx.coroutines.flow.first\n",
        "import kotlinx.coroutines.sync.withLock\n",
    ]:
        mappings.append((line, ""))
    # The original source interface, result, helpers and complete Poller survive.
    # Android network/checker wrappers are supplied by the existing retained Root.
    return adapt(original, mappings)


def state_store(repo):
    sources, _ = fixed_sources(repo)
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
    sources, _ = fixed_sources(repo)
    return adapt(sources["DynamicPersonalFeedApi.kt"], [
        ("object DynamicPersonalFeedApi {",
         "internal class DesktopDynamicPersonalFeedApi(private val request: suspend (String, ByteArray) -> ByteArray) {"),
        ('    private const val PATH = "/bilibili.app.dynamic.v2.Dynamic/DynAllPersonal"',
         '    private val PATH = "/bilibili.app.dynamic.v2.Dynamic/DynAllPersonal"'),
        ("BiliGrpcClient.request(PATH, buildDynamicPersonalFeedRequest(hostUid, offset, page))",
         "request(PATH, buildDynamicPersonalFeedRequest(hostUid, offset, page))"),
    ])


def original_tests(repo):
    sources, _ = fixed_sources(repo)
    return {name: adapt(sources[name], []) for name in
            ("MessageNotificationPolicyTest.kt", "DynamicPersonalFeedApiTest.kt")}


def generate(repo, output):
    output = Path(output)
    files = []
    proofs = []
    for path, fn in [
        ("com/android/purebilibili/feature/message/notification/MessageNotificationPolicy.kt", pure_policy),
        ("com/android/purebilibili/feature/message/notification/MessageNotificationChecker.kt", notification_poller),
        ("com/android/purebilibili/feature/message/notification/MessageNotificationStateStore.kt", state_store),
        ("com/android/purebilibili/core/network/grpc/DesktopDynamicPersonalFeedApi.kt", personal_feed),
    ]:
        body, proof = fn(repo)
        dest = output / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(body.encode("utf-8"))
        files.append(path)
        proofs.append(dict(path=path, **proof))
    for name, (body, proof) in original_tests(repo).items():
        package = "com/android/purebilibili/feature/message/notification" if name == "MessageNotificationPolicyTest.kt" else "com/android/purebilibili/core/network/grpc"
        path = "policy-tests/" + package + "/" + name
        dest = output / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(body.encode("utf-8"))
        proofs.append(dict(path=path, originalTestCases=body.count("@Test"), **proof))
    (output / "v038-message-notification-source-proof.json").write_bytes((json.dumps(proofs, indent=2)+"\n").encode())
    return files
