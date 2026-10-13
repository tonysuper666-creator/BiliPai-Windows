"""Fixed original v036 comment viewports and static skeleton declarations."""
from pathlib import Path
import hashlib,json
COMMIT='3608e0347ba3314ac74190d724d78598fdbad879'
MANIFEST_SHA='02ed993667a5daa4157e55b5699de36190d6ed673df5669dabeafaa5e06d0c10'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt', 'filename': 'ContentLoadingSkeletons.kt', 'bytes': 24990, 'sha256Bytes': 'a2e9366a7514dd80fe5ad2bed532c46610c22a901092c7b2d8b0968246e2ef28', 'gitBlob': 'd0c857546b415c14a90b10bc6df50570946b61c6'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt', 'filename': 'DynamicDetailScreen.kt', 'bytes': 49575, 'sha256Bytes': 'c4c7691fdb5f4bf1a705bd97e3c1c72d4503972c55604744b88aa9fe989e25c4', 'gitBlob': 'f09f681b229721793c6e0a5b6158a1a7db027449'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt', 'filename': 'DynamicCommentSheet.kt', 'bytes': 79680, 'sha256Bytes': 'adff166b03b65795d63ee776a4201469f80ea060f3b6ef6d41749326751f9e97', 'gitBlob': 'abb16a3b41b8b4bd553d4ba40a6c7564f3275c23'}]

def fixed_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-comment-viewport'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed comment viewport manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown comment viewport source contract')
    sources={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('Original comment viewport bytes changed')
        sources[row['path']]=raw.decode('utf-8')
    return sources

def selected_source(path,canonical):
    return fixed_sources().get(path,canonical)

def skeleton_declarations(parser,appearance):
    source=fixed_sources()['app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt']
    return appearance.declarations(parser,source,['rememberContentSkeletonPulse', 'rememberContentSkeletonBlockColor', 'ContentSkeletonBlock', 'rememberStaticSkeletonBlockColor', 'rememberCommentSkeletonBlockColor', 'resolveCommentSkeletonBodyLineCount', 'CommentListItemSkeleton', 'CommentListColumnSkeleton', 'CONTENT_SKELETON_PULSE_DURATION_MILLIS', 'CONTENT_SKELETON_LIGHT_MIN_ALPHA', 'CONTENT_SKELETON_LIGHT_MAX_ALPHA', 'CONTENT_SKELETON_DARK_MIN_ALPHA', 'CONTENT_SKELETON_DARK_MAX_ALPHA', 'STATIC_SKELETON_LIGHT_ALPHA', 'STATIC_SKELETON_DARK_ALPHA', 'COMMENT_SKELETON_HORIZONTAL_PADDING_DP', 'COMMENT_SKELETON_TOP_PADDING_DP', 'COMMENT_SKELETON_BOTTOM_PADDING_DP', 'COMMENT_SKELETON_AVATAR_SIZE_DP', 'COMMENT_SKELETON_AVATAR_GAP_DP', 'COMMENT_SKELETON_NAME_HEIGHT_DP', 'COMMENT_SKELETON_NAME_GAP_DP', 'COMMENT_SKELETON_META_HEIGHT_DP', 'COMMENT_SKELETON_BODY_GAP_DP', 'COMMENT_SKELETON_BODY_HEIGHT_DP', 'COMMENT_SKELETON_BODY_LINE_GAP_DP', 'COMMENT_SKELETON_ACTION_GAP_DP', 'COMMENT_SKELETON_ACTION_HEIGHT_DP', 'COMMENT_SKELETON_ACTION_BAR_HEIGHT_DP'])
