"""Original v041 status badges on the retained Windows personal card sources."""
from pathlib import Path
import hashlib
import json
from v025_source_paths import canonical_source

COMMIT = 'd18fb20e31adb11bbec9fae6dfce7d705d556217'
ARCHIVE = 'desktop/upstream-slices/v041-video-status-personal-consumers'
MANIFEST_SHA256 = '80ed5aaf94febad3403323b98a45299fe06efde30683963a7a8316cb8a336d15'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/list/FavoritePersonalCard.kt', 'filename': 'FavoritePersonalCard.kt', 'bytes': 10828, 'sha256': '1a14e2d8a5b1f254160828dd85c3d8b381383e9d1bb58f239746c8b99a0a3ad0', 'gitBlob': 'e4fb9da0cef3ad4927f336029bb1dfeec40f0ddc', 'canonicalSha256LF': '3f47f7ec3afbf01e8ae9992c7761fab0ddc9edb1b0334452c5e864e38e14a319'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/list/HistoryPersonalCard.kt', 'filename': 'HistoryPersonalCard.kt', 'bytes': 18781, 'sha256': '715e5dcb0c7fc856d6f324c29c9316d4ad971529f18d85023ecbe017e21f5a65', 'gitBlob': '82d72baa26f4b3000cdf996acdfbba08d9c0c836', 'canonicalSha256LF': '71dafbef774d4fd3d3538a76b7ab887f178381992393b0802bd41bc17ca2f5f5'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileScreen.kt', 'filename': 'ProfileScreen.kt', 'bytes': 224474, 'sha256': 'b6b08bd10ab5212007618803a176f0898efa68c392a2a8205804f7ba606b5ae5', 'gitBlob': 'af86354fca16a16463b7f5989966d1ba06eb843f', 'canonicalSha256LF': 'e39d9e4b1115ee9530345861e1ff4f5acdeead4552e51930e7e9410804869c47'}]
PATCHES = {'app/src/main/java/com/android/purebilibili/feature/list/FavoritePersonalCard.kt': [{'before': 'import com.android.purebilibili.core.ui.AppSpacingTokens\n', 'after': 'import com.android.purebilibili.core.ui.AppSpacingTokens\nimport com.android.purebilibili.core.ui.components.VideoStatusBadges\n', 'upstreamBlock': 'import com.android.purebilibili.core.ui.components.VideoStatusBadges\n'}, {'before': '\n        coverOverlayContent = {\n', 'after': '\n        coverOverlayContent = {\n            VideoStatusBadges(\n                labels = item.statusLabels,\n                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n            )\n', 'upstreamBlock': '\n        coverOverlayContent = {\n            VideoStatusBadges(\n                labels = item.statusLabels,\n                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n            )\n'}], 'app/src/main/java/com/android/purebilibili/feature/list/HistoryPersonalCard.kt': [{'before': 'import com.android.purebilibili.core.ui.AppSpacingTokens\n', 'after': 'import com.android.purebilibili.core.ui.AppSpacingTokens\nimport com.android.purebilibili.core.ui.components.VideoStatusBadges\n', 'upstreamBlock': 'import com.android.purebilibili.core.ui.components.VideoStatusBadges\n'}, {'before': '\n            coverOverlayContent = {\n', 'after': '\n            coverOverlayContent = {\n                VideoStatusBadges(\n                    labels = video.statusLabels,\n                    modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n                )\n', 'upstreamBlock': '\n            coverOverlayContent = {\n                VideoStatusBadges(\n                    labels = video.statusLabels,\n                    modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n                )\n'}, {'before': '\n        coverOverlayContent = {\n', 'after': '\n        coverOverlayContent = {\n            VideoStatusBadges(\n                labels = video.statusLabels,\n                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n            )\n', 'upstreamBlock': '\n        coverOverlayContent = {\n            VideoStatusBadges(\n                labels = video.statusLabels,\n                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n            )\n'}], 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileScreen.kt': [{'before': '                                        coverContent = {\n                                            AsyncImage(model = video.pic, contentDescription = null,\n                                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())\n                                        },\n', 'after': '                                        coverContent = {\n                                            AsyncImage(model = video.pic, contentDescription = null,\n                                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())\n                                        },\n                                        coverOverlayContent = {\n                                            com.android.purebilibili.core.ui.components.VideoStatusBadges(\n                                                labels = video.statusLabels,\n                                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),\n                                            )\n                                            com.android.purebilibili.feature.home.components.cards.VideoCardCoverDurationText(\n                                                text = video.length,\n                                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),\n                                            )\n                                        },\n', 'upstreamBlock': '                                        coverOverlayContent = {\n                                            com.android.purebilibili.core.ui.components.VideoStatusBadges(\n                                                labels = video.statusLabels,\n                                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),\n                                            )\n                                            com.android.purebilibili.feature.home.components.cards.VideoCardCoverDurationText(\n                                                text = video.length,\n                                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),\n                                            )\n                                        },\n'}]}

def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / "manifest.json").read_bytes()
    if digest(raw) != MANIFEST_SHA256:
        raise ValueError("Personal video badge source manifest changed")
    manifest = json.loads(raw)
    if manifest["upstreamCommit"] != COMMIT or manifest["files"] != PINS:
        raise ValueError("Personal video badge source inventory changed")
    result = {}
    for row in PINS:
        raw = (root / row["filename"]).read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        if len(raw) != row["bytes"] or digest(raw) != row["sha256"] or blob != row["gitBlob"]:
            raise ValueError("Personal video badge source identity changed: " + row["path"])
        text = raw.decode("utf-8")
        for edit in PATCHES[row["path"]]:
            if edit["upstreamBlock"] not in text:
                raise ValueError("Personal video badge block is not exact original source")
        result[row["path"]] = text
    return result


def apply(repo, path, body, output):
    if path not in PATCHES:
        return body
    fixed(repo)
    row = next(row for row in PINS if row["path"] == path)
    if digest(body.encode()) != row["canonicalSha256LF"]:
        raise ValueError("Personal video badge canonical source changed: " + path)
    before = body
    edits = []
    for edit in PATCHES[path]:
        old, new = edit["before"], edit["after"]
        if body.count(old) != 1:
            raise ValueError("Personal video badge source anchor changed: " + path)
        offset = body.index(old)
        edits.append(dict(offset=offset, before=old, after=new))
        body = body[:offset] + new + body[offset + len(old):]
    inverse = body
    for edit in reversed(edits):
        offset = edit["offset"]
        if inverse[offset:offset + len(edit["after"])] != edit["after"]:
            raise ValueError("Personal video badge inverse span changed")
        inverse = inverse[:offset] + edit["before"] + inverse[offset + len(edit["after"]):]
    if inverse != before:
        raise ValueError("Personal video badge complete source inverse failed")
    proof = dict(path=path, upstreamCommit=COMMIT, originalRawSourceSHA256=row["sha256"],
                 beforeSha256LF=digest(before.encode()), afterSha256LF=digest(body.encode()),
                 exactCompleteInverse=True, inverseEdits=edits, sourceQualified=False)
    target = Path(output) / "v041-personal-video-status-proofs" / (Path(path).name + ".json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return body


def generate(repo, output):
    repo, output = Path(repo), Path(output)
    fixed(repo)
    result = []
    for row in PINS:
        if "/feature/list/" not in row["path"]:
            continue
        body = canonical_source(repo, row["path"]).read_bytes().replace(b"\r\n", b"\n").decode("utf-8")
        body = apply(repo, row["path"], body, output)
        relative = row["path"].removeprefix("app/src/main/java/")
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding="utf-8", newline="\n")
        result.append(relative)
    return result
