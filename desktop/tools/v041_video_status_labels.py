"""Exact v0.4.1 video status labels, whole replacement models and anchored consumer hooks.

Authority is jay3-yy/BiliPai v0.4.1 d18fb20e (VideoStatusLabels 8115954f, VideoStatusBadges 68e9bc71).
resolveVideoStatusLabels orders (charging xor paid), course, cooperation, interactive and then server
labels; it trims, drops blanks, maps 课程 to 课堂 and 充电 to 充电专属, keeps exact-string distinct
entries and has no cap. v0.4.1 has no 订阅 label, 置顶 is a separate TopStart chip of the Space top card
outside this policy, and 付费 is never produced from search, space or watch-later fields.
Canonical v025 text stays intact: four models are emitted beside it (Sync excludes), and every card,
screen and repository edit is an exact-once hook that the sole generator of that file applies first.
Decode parity with upstream: badges stay List<VideoContentBadge>, so a non-object element still fails
the whole page; any non-null charging_pay.level (including 0) is charging, as in the upstream tests.
"""
from collections import namedtuple
from pathlib import Path
import hashlib
import json
import os
import re
from v025_source_paths import canonical_source
import v043_search_up_author as search_up_followup

COMMIT = 'd18fb20e31adb11bbec9fae6dfce7d705d556217'
BASELINE_COMMIT = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
ARCHIVE = 'desktop/upstream-slices/v041-video-status-labels'
MANIFEST_SHA256 = 'e13914083ff47f1ba60181bda99cd71f6b019a71488b8bf2ff323c9c54f3b9d3'
CORE = 'core-data/src/main/java/com/android/purebilibili/'
APP = 'app/src/main/java/com/android/purebilibili/'
MODELS = CORE + 'data/model/response/'
LABELS = MODELS + 'VideoStatusLabels.kt'
BADGES = APP + 'core/ui/components/VideoStatusBadges.kt'
LIST_MODELS = MODELS + 'ListModels.kt'
SPACE_MODELS = MODELS + 'SpaceModels.kt'
SEARCH_MODELS = MODELS + 'SearchModels.kt'
FAVORITE_MODELS = MODELS + 'FavoriteModels.kt'
USER_CONTENT = CORE + 'data/repository/UserContentRepository.kt'
TEST_ROOT = 'core-data/src/test/java/com/android/purebilibili/data/model/response/'
LABELS_TEST = TEST_ROOT + 'VideoStatusLabelsTest.kt'
COOPERATION_TEST = TEST_ROOT + 'VideoCooperationMetadataTest.kt'
VIDEO_CARD = APP + 'feature/home/components/cards/VideoCard.kt'
SINGLE_COLUMN = APP + 'feature/home/components/cards/HomeStyleSingleColumnVideoCard.kt'
SPACE_SCREEN = APP + 'feature/space/SpaceScreen.kt'
SPACE_LOAD_POLICY = APP + 'feature/space/SpaceLoadPolicy.kt'
WATCH_LATER_REPOSITORY = CORE + 'data/repository/WatchLaterRepository.kt'
WATCH_LATER_SCREEN = APP + 'feature/watchlater/WatchLaterScreen.kt'
SUPPORT_PATH = 'core-data/src/test/java/com/android/purebilibili/data/repository/UserContentVideoItem.kt'
PROOF_NAME = 'v041-video-status-labels-proof.json'

PRODUCER = 'desktop/tools/extract-v041-video-status-labels.py'
HOME_CARD_GENERATOR = 'desktop/tools/extract-upstream-home-full-card.py'
SPACE_PAGES_GENERATOR = 'desktop/tools/extract-upstream-space-pages.py'
SPACE_OVERVIEW_GENERATOR = 'desktop/tools/extract-space-overview.py'
WATCH_LATER_GENERATOR = 'desktop/tools/extract-upstream-watchlater.py'

PINS = [
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/VideoStatusLabels.kt', 'archiveFile': 'VideoStatusLabels.kt', 'bytes': 903, 'sha256': 'e5cba87c8ee8440d02b87c777ca545ab5d43408ce3f1d2f0d309b5cb13ef60d0', 'gitBlob': '8115954f021f4a5702ab2f94eac790074b32768f', 'referenceOnly': False},
    {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/VideoStatusBadges.kt', 'archiveFile': 'VideoStatusBadges.kt', 'bytes': 1023, 'sha256': '83cf025c274e340e8877986784ffb1d9bbb88a168ef6b81c830344a601cf854b', 'gitBlob': '68e9bc71e5109897d0b8083c64f0c8135508e546', 'referenceOnly': False},
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/ListModels.kt', 'archiveFile': 'ListModels.kt', 'bytes': 24435, 'sha256': '3da884027dae2c641ac4deb90f5149dc9462d1b5d786f8e7db547f16f94e75f1', 'gitBlob': 'c0db297c727a424c0f59dc57b06a6126cff6dbaa', 'referenceOnly': False},
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/SpaceModels.kt', 'archiveFile': 'SpaceModels.kt', 'bytes': 30291, 'sha256': '3fdd9795a060157922f5bbfa28c705d193fd401247e5d975b0f60eefdedcaf4b', 'gitBlob': '48f8347e406193fb6d5061c9f0a335d75a70b1e0', 'referenceOnly': False},
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/SearchModels.kt', 'archiveFile': 'SearchModels.kt', 'bytes': 20659, 'sha256': '121a06212ebe034ffb274d822dce5fd5a685113e8984ff5554ab6b3006a94a30', 'gitBlob': 'bfa90f39597c1639b37b7b255baf4f7975043dce', 'referenceOnly': False},
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/FavoriteModels.kt', 'archiveFile': 'FavoriteModels.kt', 'bytes': 6056, 'sha256': '3b5a648f0b9625019f173afbd4981d81313919c64dbea8140602a8437d595534', 'gitBlob': '8e8ce0f0dc335748c66904076c1d7c809c2f3ea7', 'referenceOnly': False},
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/UserContentRepository.kt', 'archiveFile': 'UserContentRepository.kt', 'bytes': 3918, 'sha256': 'b95f08c8f693d0ce7a089a52df4f9884c564ae968393ece23488c09732eca3dd', 'gitBlob': '8301c8b661f897772bcc56df10bd35d026fb39a4', 'referenceOnly': True},
    {'path': 'core-data/src/test/java/com/android/purebilibili/data/model/response/VideoStatusLabelsTest.kt', 'archiveFile': 'VideoStatusLabelsTest.kt', 'bytes': 2638, 'sha256': '321d1502204a020546396f474f5cef38c35e78793db184f3a73a4c8fd1090c7c', 'gitBlob': '1bf5b2e40dcb517ae61c9a128e001ab98d75f1e1', 'referenceOnly': False},
    {'path': 'core-data/src/test/java/com/android/purebilibili/data/model/response/VideoCooperationMetadataTest.kt', 'archiveFile': 'VideoCooperationMetadataTest.kt', 'bytes': 1814, 'sha256': '4c8a476ec72a7b3013c8a77504fe946e5b578807937210c4ddb355edd926e0fa', 'gitBlob': '4ee8b2b4e5c4fe083e8eea7ca26e02a274757e0b', 'referenceOnly': False},
    {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt', 'archiveFile': 'VideoCard.kt', 'bytes': 101977, 'sha256': '5dd884fc431ca1809a4e766bb6492b862f56b9a59462348093aa1fc55e7bb78d', 'gitBlob': '5450beeaafd86f416e18cdaffffc29c900f684d5', 'referenceOnly': True},
    {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/cards/HomeStyleSingleColumnVideoCard.kt', 'archiveFile': 'HomeStyleSingleColumnVideoCard.kt', 'bytes': 17665, 'sha256': '8b910cfbe6dd48b20ffa9dcff0109b99c7554fdadd073da828096a78b1ae9a42', 'gitBlob': '31d3a950c38b0761897a203e669a2e4d78547302', 'referenceOnly': True},
    {'path': 'app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt', 'archiveFile': 'SpaceScreen.kt', 'bytes': 259397, 'sha256': 'c091fe781a4548ee46af7750d92ba7881eaee3c8f21da4ad3fa0fe14c47c838d', 'gitBlob': '122866cff2790f782d430a226c80cc29597ad844', 'referenceOnly': True},
    {'path': 'app/src/main/java/com/android/purebilibili/feature/space/SpaceLoadPolicy.kt', 'archiveFile': 'SpaceLoadPolicy.kt', 'bytes': 32756, 'sha256': '108e14a0f2316477e6da3df04ae44ca5e9b75fe0b64db25df42ce01697fff240', 'gitBlob': 'ef511508d88bfa81689b409985208a9af15b6f2c', 'referenceOnly': True},
    {'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/WatchLaterRepository.kt', 'archiveFile': 'WatchLaterRepository.kt', 'bytes': 6402, 'sha256': '088b5ea5761f2d709bcb59218a4cd1ca437c434b318066a8c59593ee2dccac28', 'gitBlob': 'ead77564591d2c93a6d52ff3398f004d0d66abfa', 'referenceOnly': True},
    {'path': 'app/src/main/java/com/android/purebilibili/feature/watchlater/WatchLaterScreen.kt', 'archiveFile': 'WatchLaterScreen.kt', 'bytes': 83794, 'sha256': '9e7715de37f1e4ad3741f07c6006c2289d94cfc610809e539210d7f9d4e3511b', 'gitBlob': 'f689fa2592c352a59313cab50b4a22b631550ecf', 'referenceOnly': True},
]
BASELINE_SHA256_LF = {
    LIST_MODELS: 'edca7acceea286c44cbffbaf30e818e4b6d3544d6e2252332abddb6f3b76b88c',
    SPACE_MODELS: '9e77b72177ab540e523dcf38fc1923d8500cefb83d8047b1aaaff8a10503af37',
    SEARCH_MODELS: '746c0519ac24c500bced45d7007f254c047033af3eed6083be2128bf683bf4eb',
    FAVORITE_MODELS: '6c3adf44d672a00ea73c5e289c86198c3d637c53cf65fe56fb49a81c9ff39f27',
    VIDEO_CARD: '4486fe0d03db8218921f662f5036566ab56243d97b2df5d14c9f4e628beb7938',
    SINGLE_COLUMN: '0791f3f41c662f0e9eb6d196593637bc8d832122deafe6a4cf3d05f541072f78',
    SPACE_SCREEN: '4fa39d30048c5d3c371c0a7b44258370825c93b1e60e321bc8663e92e9311a2e',
    SPACE_LOAD_POLICY: 'fad17890cf2f8a2d95fc80a706c6e66011dd992df3511d77ef957b1d90ed2c8c',
    WATCH_LATER_REPOSITORY: '2475d0ddf99fb57e0c0c0edba851de048b2f111f3bac450d8934f4da03cb3f72',
    WATCH_LATER_SCREEN: '266e5f40e13147e8ffafc06537966174dcf0f492eac8cde455070a45885b6f84',
}

# Reviewed baseline -> v0.4.1 hunks of the two whole-file replacements, as line ranges plus
# SHA-256 of the replaced and the replacing text; replayed forward and inverse, so the table must be complete.
WHOLE_MODEL_DELTAS = {
    LIST_MODELS: [
        (218, 219, 218, 254, 'ba5ec51d07a4ac0e951608704431d59a02b21a4e951acc10505a8dc407c501ee', '690f36e3d76e5c3e145951d01f699d684cb04cae512ba50d31abaa1444a18d4d', 'VideoItem label inputs, isCooperation and the statusLabels getter'),
        (261, 262, 296, 303, '820d6cafe0e5595a1d3e696bfc8b46d5c5102e7b4669417c7712061cf7a269fb', '07fb81bebd6d9ed781e6816ab278f72c3061b089401fa4fb7fb57bea32391717', 'VideoRights is_cooperation and is_stein_gate'),
        (327, 327, 368, 371, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'a4c7d6e7f2aa59ce7060bb5906c58516cd63d38873ccf660ce8d3d1f738dffba', 'RecommendItem charging_pay, pgc_label and badges fields'),
        (354, 355, 398, 399, 'd12407eafd2b3e2fa0c6a9af9a3678a56158ebbef2a8667d7d95a9fa6ce15c1f', '79af15d8ddf6b58c8dc2b72858342dd5359ea3f6197de20b65319ebbc14cf6f9', 'RecommendItem.toVideoItem passes the label inputs'),
        (402, 403, 446, 450, '935521c27b0f4cedd8d5e965c99f4dbff4b74d2b4c251d1a3dde1785f7112431', '9132e2a4b731d2927ec3ecaa6a751f141e2c1f81741766f04cab4ca916c6fa12', 'PopularItem charging_pay, pgc_label and badges fields'),
        (427, 428, 474, 475, 'd12407eafd2b3e2fa0c6a9af9a3678a56158ebbef2a8667d7d95a9fa6ce15c1f', '79af15d8ddf6b58c8dc2b72858342dd5359ea3f6197de20b65319ebbc14cf6f9', 'PopularItem.toVideoItem passes the label inputs'),
        (550, 550, 597, 600, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'a4c7d6e7f2aa59ce7060bb5906c58516cd63d38873ccf660ce8d3d1f738dffba', 'DynamicRegionItem charging_pay, pgc_label and badges fields'),
        (574, 575, 624, 625, 'd12407eafd2b3e2fa0c6a9af9a3678a56158ebbef2a8667d7d95a9fa6ce15c1f', '79af15d8ddf6b58c8dc2b72858342dd5359ea3f6197de20b65319ebbc14cf6f9', 'DynamicRegionItem.toVideoItem passes the label inputs'),
        (637, 638, 687, 692, 'f62650bfb0d32ce0323753b04f48b77a4879aafab9c2df80246e2fa0e952661a', '3c67bc4c487db30eb6673841e62222813d02bdb7594d24aab2709248298bf0d3', 'MobileFeedItem cover_right_text, rights, charging_pay and badges fields'),
        (673, 673, 727, 732, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'c7b62a93baa3fca34383d30d54ba93e963d09dadacb5341ed5cecacc3656050e', 'MobileFeedItem.toVideoItem passes rights, charging_pay, the bangumi-only pgc_label and badges'),
    ],
    SPACE_MODELS: [
        (302, 304, 302, 321, 'ad7ad7f750665a31e98f926e12fc741b21c7360d31f310988ce961196d14e361', '8bb0e45ab2385c9dac168c5744deb3c1236924c625a52a4f5fdd72ec9e06f02e', 'SpaceAggregateArchiveItem is_cooperation, is_steins, is_lesson_video and the statusLabels getter'),
        (411, 413, 428, 452, '06a7942da8eb482babbd6263eff7c7714f3496d152b7b89b407477891581e472', 'bcf83ae8e170f2177234b859b032ee145107f239506889df6adbedd6793fc8f6', 'SpaceVideoItem is_union_video, is_cooperation, is_steins, is_lesson_video, badges and the statusLabels getter'),
    ],
}

# Reviewed remainders after label hooks. Search still needs its full feature chain; Favorite is emitted whole.
REVIEWED_POST_HOOK_DELTAS = {
    SEARCH_MODELS: [
        (110, 110, 110, 133, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '43b32602355a130bbdcec1395b444bf9206195a63bff41b9f20f3e722276ba8d', 'search/all/v2 SearchAllResponse, SearchAllData and SearchAllResultCategory (separate search-all feature)'),
        (412, 412, 435, 436, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'a420ad0aeb2eacea75e3829ed663e9e8bd3f84b1684909a255afa54e02dbfaa5', 'SearchUpItem.mid Flexible serializer'),
        (416, 416, 440, 441, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'c6e4f2d5fe9b2b37b73ee5ab00a7f36ef042967427ecd9dbd6274937b68bec13', 'SearchUpItem.fans Flexible serializer'),
        (417, 417, 442, 443, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'c6e4f2d5fe9b2b37b73ee5ab00a7f36ef042967427ecd9dbd6274937b68bec13', 'SearchUpItem.videos Flexible serializer'),
        (418, 418, 444, 445, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'c6e4f2d5fe9b2b37b73ee5ab00a7f36ef042967427ecd9dbd6274937b68bec13', 'SearchUpItem.level Flexible serializer'),
        (421, 422, 448, 451, 'b1336cd449bb42afc2e2d907e6f1850ef069552b54ab9608423dc729cbfcabe5', '7b2177dd7cf6391263eac1eb42aa524b0f5c9ea874778acdbacc9100dd7b9e50', 'SearchUpItem.is_senior_member Flexible serializer and res field'),
        (552, 552, 581, 582, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '072e3e8f630da45e24be4bc421871630d345227fa95a0e26b9d240ef4a9393ee', 'SearchLiveUserItem area_name field'),
        (558, 559, 588, 590, 'e289210d06355bfb1cf0f3b37061446b277073640d939768feef457c32dc8cdb', 'a256ce131a3dbe5f291dd30313894095f625d80e3af482921f9496d76f4c987f', 'SearchLiveUserItem.cleanupFields cleans area_name'),
        (571, 571, 602, 603, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'a6fb8a7c52053643b465fc551afd7c0d20314c7cf5ae0a45ddb6ea0bf18d49c5', 'SearchType.ALL (resolveSearchFilterTabs appends every entry, so an unhandled comprehensive tab would appear)'),
    ],
    FAVORITE_MODELS: [
        (172, 172, 172, 173, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '0bea6eb0d43cfaabd9aa0177c1798883ef0e514dacc78a36af036ca65802be2b', 'WatchLaterData.ps (DesktopPersonalContentTest builds WatchLaterData(60, list) positionally)'),
    ],
}

# Label-chain needles whose counts in the emitted text must equal the archived v0.4.1 blob.
PARITY_NEEDLES = {
    LABELS: (
        'if (charging) add("充电专属") else if (paid) add("付费")', 'if (course) add("课堂")',
        'if (cooperation) add("合作")', 'if (interactive) add("互动")', '.distinct()',
        'data class VideoContentBadge(val text: String = "")',
    ),
    BADGES: ('emphasized = label == "充电专属" || label == "付费"', 'FlowRow(', 'AppStatusBadge('),
    LIST_MODELS: (
        'val statusLabels: List<String>', 'chargingPay?.level != null', 'List<VideoContentBadge>',
        'rights?.isSteinGate == 1', 'contentType == "ketang"', 'contentType == "live_room"',
        'goto == "bangumi"',
    ),
    SPACE_MODELS: (
        'val statusLabels: List<String>', 'List<SpaceAggregateBadge>', 'isUnionVideo == 1', 'data class SpaceAggregateBadge(',
    ),
    SEARCH_MODELS: ('val isUnionVideo: Int = 0', 'List<VideoContentBadge>', 'badges = badges,', 'pgcLabel = pgcLabel,'),
    FAVORITE_MODELS: ('val isSteinGate: Int? = null', '@SerialName("is_stein_gate")'),
    VIDEO_CARD: (
        'VideoStatusBadges(', 'HomeVideoBadgePill(', 'premiumBadgeLabel', 'resolveVideoPremiumBadgeLabel(',
        'video.statusLabels',
    ),
    SINGLE_COLUMN: ('VideoStatusBadges(', 'video.statusLabels'),
    SPACE_SCREEN: (
        'VideoStatusBadges(', 'statusLabels', 'statusLabels = ', 'resolveSpaceVideoChargeBadgeLabel',
        'badgeLabel = "置顶"', 'badgeLabel: String? = null', 'premiumBadgeText = badgeLabel.orEmpty()',
    ),
    SPACE_LOAD_POLICY: ('isCooperation = item.isCooperation', 'SpaceAggregateBadge(', 'badges = item.badges'),
    WATCH_LATER_REPOSITORY: ('VideoRights', 'contentType', 'val badges = buildList', 'rights = VideoRights(', 'pgcLabel = item.pgcLabel'),
    WATCH_LATER_SCREEN: ('VideoStatusBadges(', 'item.statusLabels', 'overlineContent', 'item.contentType'),
}
# Every string literal of the shared policy and badge row; v0.4.1 has neither 订阅 nor 置顶 in them.
LABEL_VOCABULARY = ('互动', '付费', '充电', '充电专属', '合作', '课堂', '课程')

# Deferred upstream text: present in v0.4.1, absent from the emitted replacement.
ABSENT_FROM_EMITTED = {}

Hook = namedtuple('Hook', 'id path name consumer before after')
HOOKS = (
    Hook('SM1', SEARCH_MODELS, 'SearchVideoItem label fields', PRODUCER,
         (
            '    val typeId: Int = 0\n'
            ') {\n'
            '    fun toVideoItem(): VideoItem {\n'
        ),
         (
            '    val typeId: Int = 0,\n'
            '    @SerialName("is_union_video")\n'
            '    @Serializable(with = FlexibleIntSerializer::class)\n'
            '    val isUnionVideo: Int = 0,\n'
            '    @SerialName("is_steins")\n'
            '    @Serializable(with = FlexibleBooleanSerializer::class)\n'
            '    val isSteins: Boolean = false,\n'
            '    @SerialName("is_lesson_video")\n'
            '    @Serializable(with = FlexibleBooleanSerializer::class)\n'
            '    val isLessonVideo: Boolean = false,\n'
            '    @SerialName("charging_pay") val chargingPay: WatchLaterChargingPay? = null,\n'
            '    @SerialName("pgc_label") val pgcLabel: String? = null,\n'
            '    val badges: List<VideoContentBadge> = emptyList()\n'
            ') {\n'
            '    fun toVideoItem(): VideoItem {\n'
        )),
    Hook('SM2', SEARCH_MODELS, 'toVideoItem label arguments', PRODUCER,
         (
            '            navigationUrl = arcurl,\n'
            '            tid = typeId\n'
        ),
         (
            '            navigationUrl = arcurl,\n'
            '            isUnionVideo = isUnionVideo,\n'
            '            isSteins = isSteins,\n'
            '            isLessonVideo = isLessonVideo,\n'
            '            chargingPay = chargingPay,\n'
            '            pgcLabel = pgcLabel,\n'
            '            badges = badges,\n'
            '            tid = typeId\n'
        )),
    Hook('FM1', FAVORITE_MODELS, 'WatchLaterRights is_stein_gate', PRODUCER,
         (
            '    @SerialName("is_cooperation") val isCooperation: Int? = null,\n'
            ')\n'
        ),
         (
            '    @SerialName("is_cooperation") val isCooperation: Int? = null,\n'
            '    @SerialName("is_stein_gate") val isSteinGate: Int? = null,\n'
            ')\n'
        )),
    Hook('HC1', VIDEO_CARD, 'VideoStatusBadges import', HOME_CARD_GENERATOR,
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
        ),
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
            'import com.android.purebilibili.core.ui.components.VideoStatusBadges\n'
        )),
    Hook('HC2', VIDEO_CARD, 'premium pill to VideoStatusBadges', HOME_CARD_GENERATOR,
         (
            '            if (premiumBadgeLabel != null) {\n'
            '                HomeVideoBadgePill(\n'
            '                    style = badgeStylePolicy.coverStyle,\n'
            '                    useRealtimeHaze = badgeEffectVisual.useRealtimeHaze,\n'
            '                    shape = AppShapes.container(ContainerLevel.Tag),\n'
            '                    containerColor = BiliPink.copy(alpha = if (badgeStylePolicy.coverStyle == HomeVideoBadgeStyle.GLASS) 0.78f else 1f),\n'
            '                    borderColor = MediaContrastPalette.Foreground.copy(alpha = 0.24f),\n'
            '                    modifier = Modifier\n'
            '                        .align(Alignment.TopEnd)\n'
            '                        .padding(AppSpacingTokens.Small)\n'
            '                ) {\n'
            '                    AppText(\n'
            '                        text = premiumBadgeLabel,\n'
            '                        color = MediaContrastPalette.Foreground,\n'
            '                        fontSize = MaterialTheme.typography.labelSmall.fontSize,\n'
            '                        fontWeight = FontWeight.Bold,\n'
            '                        maxLines = 1\n'
            '                    )\n'
            '                }\n'
            '            }\n'
        ),
         (
            '            VideoStatusBadges(\n'
            '                labels = video.statusLabels,\n'
            '                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.Small),\n'
            '            )\n'
        )),
    Hook('HC3', SINGLE_COLUMN, 'VideoStatusBadges import', HOME_CARD_GENERATOR,
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
        ),
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
            'import com.android.purebilibili.core.ui.components.VideoStatusBadges\n'
        )),
    Hook('HC4', SINGLE_COLUMN, 'single-column cover badges', HOME_CARD_GENERATOR,
         (
            '        coverOverlayContent = {\n'
            '            if (durationStyle == com.android.purebilibili.core.store.HomeDurationStyle.HIDDEN) {\n'
        ),
         (
            '        coverOverlayContent = {\n'
            '            VideoStatusBadges(\n'
            '                labels = video.statusLabels,\n'
            '                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n'
            '            )\n'
            '            if (durationStyle == com.android.purebilibili.core.store.HomeDurationStyle.HIDDEN) {\n'
        )),
    Hook('SS0', SPACE_SCREEN, 'VideoStatusBadges import', SPACE_PAGES_GENERATOR,
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
        ),
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
            'import com.android.purebilibili.core.ui.components.VideoStatusBadges\n'
        )),
    Hook('SS1', SPACE_SCREEN, 'grid drops the charge chip argument', SPACE_PAGES_GENERATOR,
         (
            '                                            coverAspectRatio = spaceFeedCoverAspectRatio,\n'
            '                                            badgeLabel = resolveSpaceVideoChargeBadgeLabel(video),\n'
        ),
         (
            '                                            coverAspectRatio = spaceFeedCoverAspectRatio,\n'
        )),
    Hook('SS2', SPACE_SCREEN, 'list passes statusLabels', SPACE_PAGES_GENERATOR,
         (
            '                                            badgeLabel = resolveSpaceVideoChargeBadgeLabel(video),\n'
            '                                            isLocateHighlight = highlightedLocateBvid == video.bvid &&\n'
            '                                                isLocateHighlightVisible,\n'
            '                                            modifier = boundedListModifier,\n'
        ),
         (
            '                                            statusLabels = video.statusLabels,\n'
            '                                            isLocateHighlight = highlightedLocateBvid == video.bvid &&\n'
            '                                                isLocateHighlightVisible,\n'
            '                                            modifier = boundedListModifier,\n'
        )),
    Hook('SS3', SPACE_SCREEN, 'SpaceHomeVideoCard cover badges', SPACE_PAGES_GENERATOR,
         (
            '                    .then(nativeCardSnapshot.coverOverlayModifier),\n'
            '            ) {\n'
            '            if (!badgeLabel.isNullOrBlank()) {\n'
            '                AppSurface(\n'
            '                    modifier = Modifier\n'
            '                        .align(Alignment.TopStart)\n'
            '                        .padding(8.dp),\n'
        ),
         (
            '                    .then(nativeCardSnapshot.coverOverlayModifier),\n'
            '            ) {\n'
            '            VideoStatusBadges(\n'
            '                labels = video.statusLabels,\n'
            '                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),\n'
            '            )\n'
            '            if (!badgeLabel.isNullOrBlank()) {\n'
            '                AppSurface(\n'
            '                    modifier = Modifier\n'
            '                        .align(Alignment.TopStart)\n'
            '                        .padding(8.dp),\n'
        )),
    Hook('SS4', SPACE_SCREEN, 'SpaceAggregateMediaCard cover badges', SPACE_PAGES_GENERATOR,
         (
            '            if (item.length.isNotBlank()) {\n'
            '                VideoCardCoverDurationText(\n'
            '                    text = item.length,\n'
        ),
         (
            '            VideoStatusBadges(\n'
            '                labels = item.statusLabels,\n'
            '                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),\n'
            '            )\n'
            '            if (item.length.isNotBlank()) {\n'
            '                VideoCardCoverDurationText(\n'
            '                    text = item.length,\n'
        )),
    Hook('SS5', SPACE_SCREEN, 'SpaceContributionArchiveVideoItem passes statusLabels', SPACE_PAGES_GENERATOR,
         (
            '                cover = video.pic,\n'
            '                duration = video.length,\n'
            '                publishTime = FormatUtils.formatPublishTime(video.created),\n'
        ),
         (
            '                cover = video.pic,\n'
            '                duration = video.length,\n'
            '                statusLabels = video.statusLabels,\n'
            '                publishTime = FormatUtils.formatPublishTime(video.created),\n'
        )),
    Hook('SS6', SPACE_SCREEN, 'SpaceArchiveListItemRow statusLabels parameter', SPACE_PAGES_GENERATOR,
         (
            '    badgeLabel: String? = null,\n'
            '    isLocateHighlight: Boolean = false,\n'
            '    onClick: () -> Unit,\n'
        ),
         (
            '    badgeLabel: String? = null,\n'
            '    statusLabels: List<String> = emptyList(),\n'
            '    isLocateHighlight: Boolean = false,\n'
            '    onClick: () -> Unit,\n'
        )),
    Hook('SS7', SPACE_SCREEN, 'SpaceArchiveListItemRow cover badges', SPACE_PAGES_GENERATOR,
         (
            '        coverOverlayContent = {\n'
            '            if (!badgeLabel.isNullOrBlank()) {\n'
            '                AppSurface(\n'
            '                    modifier = Modifier\n'
            '                        .align(Alignment.TopStart)\n'
            '                        .padding(6.dp),\n'
        ),
         (
            '        coverOverlayContent = {\n'
            '            VideoStatusBadges(\n'
            '                labels = statusLabels,\n'
            '                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),\n'
            '            )\n'
            '            if (!badgeLabel.isNullOrBlank()) {\n'
            '                AppSurface(\n'
            '                    modifier = Modifier\n'
            '                        .align(Alignment.TopStart)\n'
            '                        .padding(6.dp),\n'
        )),
    Hook('SO1', SPACE_LOAD_POLICY, 'mapSpaceAggregateVideoItem label inputs', SPACE_OVERVIEW_GENERATOR,
         (
            'private fun mapSpaceAggregateVideoItem(item: SpaceAggregateArchiveItem): SpaceVideoItem {\n'
            '    return SpaceVideoItem(\n'
        ),
         (
            'private fun mapSpaceAggregateVideoItem(item: SpaceAggregateArchiveItem): SpaceVideoItem {\n'
            '    return SpaceVideoItem(\n'
            '        isCooperation = item.isCooperation,\n'
            '        isSteins = item.isSteins,\n'
            '        isLessonVideo = item.isLessonVideo,\n'
            '        badges = item.badges + item.label.takeIf(String::isNotBlank)\n'
            '            ?.let { listOf(com.android.purebilibili.data.model.response.SpaceAggregateBadge(it)) }.orEmpty(),\n'
        )),
    Hook('WL1', WATCH_LATER_REPOSITORY, 'VideoRights import', WATCH_LATER_GENERATOR,
         (
            'import com.android.purebilibili.core.network.NetworkModule\n'
        ),
         (
            'import com.android.purebilibili.data.model.response.VideoRights\n'
            'import com.android.purebilibili.core.network.NetworkModule\n'
        )),
    Hook('WL2', WATCH_LATER_REPOSITORY, 'drop the contentType badge text', WATCH_LATER_GENERATOR,
         (
            '                val badges = buildList {\n'
            '                    if (item.chargingPay?.level != null) add("充电")\n'
            '                    if (item.rights?.isCooperation == 1) add("合作")\n'
            '                    item.pgcLabel?.takeIf(String::isNotBlank)?.let(::add)\n'
            '                    if (item.isPugv == true) add("课程")\n'
            '                }\n'
        ),
         ''),
    Hook('WL3', WATCH_LATER_REPOSITORY, 'map rights, course, charging and pgc label', WATCH_LATER_GENERATOR,
         (
            '                    contentType = badges.joinToString(" · "),\n'
        ),
         (
            '                    rights = VideoRights(\n'
            '                        isCooperation = item.rights?.isCooperation ?: 0,\n'
            '                        isSteinGate = item.rights?.isSteinGate ?: 0,\n'
            '                    ),\n'
            '                    isLessonVideo = item.isPugv == true,\n'
            '                    chargingPay = item.chargingPay,\n'
            '                    pgcLabel = item.pgcLabel,\n'
        )),
    Hook('WL4', WATCH_LATER_SCREEN, 'VideoStatusBadges import', WATCH_LATER_GENERATOR,
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
        ),
         (
            'import com.android.purebilibili.core.ui.components.AppText\n'
            'import com.android.purebilibili.core.ui.components.VideoStatusBadges\n'
        )),
    Hook('WL5', WATCH_LATER_SCREEN, 'cover badges', WATCH_LATER_GENERATOR,
         (
            '        coverOverlayContent = {\n'
            '            val watched = item.duration > 0 && item.progress >= item.duration\n'
        ),
         (
            '        coverOverlayContent = {\n'
            '            VideoStatusBadges(\n'
            '                labels = item.statusLabels,\n'
            '                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacingTokens.ExtraSmall),\n'
            '            )\n'
            '            val watched = item.duration > 0 && item.progress >= item.duration\n'
        )),
)

TEST_IMPORTS = (
    ('import org.junit.Assert.assertEquals\n', 'import kotlin.test.assertEquals\n'),
    ('import org.junit.Assert.assertFalse\n', 'import kotlin.test.assertFalse\n'),
    ('import org.junit.Assert.assertTrue\n', 'import kotlin.test.assertTrue\n'),
    ('import org.junit.Test\n', 'import kotlin.test.Test\n'),
)
TEST_CASES = {LABELS_TEST: 6, COOPERATION_TEST: 5}
SUPPORT_MARKER = 'fun durationSeconds(text: String): Int ='
SUPPORT_DECLARATION = 'fun SpaceVideoItem.asVideoItem(mid: Long): VideoItem ='


def sha(raw):
    return hashlib.sha256(raw.encode('utf-8') if isinstance(raw, str) else raw).hexdigest()


def blob(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def need(value, message):
    if not value:
        raise ValueError(message)


def wide(path):
    path = Path(path).resolve()
    return Path('\\\\?\\' + str(path)) if os.name == 'nt' and not str(path).startswith('\\\\?\\') else path


def verified_raw(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'v041 status-label archive is missing or linked')
    need(not (root / 'manifest.json').is_symlink(), 'v041 status-label manifest is linked')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'v041 status-label manifest changed')
    manifest = json.loads(raw)
    identity = (manifest.get('schemaVersion'), manifest.get('repository'), manifest.get('tag'),
                manifest.get('commit'), manifest.get('files'))
    need(identity == (1, 'jay3-yy/BiliPai', 'v0.4.1', COMMIT, PINS), 'v041 status-label source identities changed')
    need({entry.name for entry in root.iterdir()} == {'manifest.json', *[row['archiveFile'] for row in PINS]},
         'v041 status-label archive leaves changed')
    result = {}
    for row in PINS:
        leaf = root / row['archiveFile']
        need(leaf.is_file() and not leaf.is_symlink(), 'v041 status-label source is linked: ' + row['archiveFile'])
        raw = leaf.read_bytes()
        need((len(raw), sha(raw), blob(raw)) == (row['bytes'], row['sha256'], row['gitBlob']),
             'v041 status-label source bytes changed: ' + row['archiveFile'])
        need(b'\r' not in raw and not raw.startswith(b'\xef\xbb\xbf'),
             'v041 status-label source must be LF without BOM: ' + row['archiveFile'])
        result[row['path']] = raw
    return result


def verified_sources(repo):
    return {path: raw.decode('utf-8') for path, raw in verified_raw(repo).items()}


def baseline(repo, path):
    raw = canonical_source(repo, path).read_bytes().replace(b'\r\n', b'\n')
    need(sha(raw) == BASELINE_SHA256_LF[path], 'Canonical v025 source changed: ' + path)
    return raw.decode('utf-8')


def reviewed_delta(path, table, old, new):
    """Prove the pinned hunk table is an exact, complete edit script old -> new and return its receipts."""
    old_lines, new_lines = old.split('\n'), new.split('\n')
    forward, inverse, old_at, new_at = [], [], 0, 0
    for i1, i2, j1, j2, before, after, _ in table[path]:
        need(old_at <= i1 <= i2 <= len(old_lines) and new_at <= j1 <= j2 <= len(new_lines),
             'Reviewed hunk order changed: ' + path)
        need(sha('\n'.join(old_lines[i1:i2])) == before and sha('\n'.join(new_lines[j1:j2])) == after,
             'Reviewed v0.4.1 hunks changed: ' + path)
        forward += old_lines[old_at:i1] + new_lines[j1:j2]
        inverse += new_lines[new_at:j1] + old_lines[i1:i2]
        old_at, new_at = i2, j2
    forward += old_lines[old_at:]
    inverse += new_lines[new_at:]
    need('\n'.join(forward) == new and '\n'.join(inverse) == old, 'Reviewed v0.4.1 hunks changed: ' + path)
    return [dict(oldLines=[i1, i2], newLines=[j1, j2], beforeSha256=before, afterSha256=after, meaning=meaning)
            for i1, i2, j1, j2, before, after, meaning in table[path]]


def hooks_for(path):
    return [hook for hook in HOOKS if hook.path == path]


def revert(text, edits):
    """Inverse of apply(): undo the recorded edits newest first and check every intermediate digest."""
    for edit in reversed(edits):
        offset, before, after = edit['offset'], edit['before'], edit['after']
        need(sha(text) == edit['afterSha256'] and text[offset:offset + len(after)] == after,
             'Inverse seam of ' + edit['id'] + ' changed')
        text = text[:offset] + before + text[offset + len(after):]
        need(sha(text) == edit['beforeSha256'], 'Inverse digest of ' + edit['id'] + ' changed')
    return text


def apply(path, text, output=None, output_name=None, edits=None):
    """Apply every reviewed exact-once hook registered for path in order; other paths pass through.

    Run it on the canonical text before any other generator edit (the home card generator applies it
    to its body, so the SHA header and inventory keep describing the canonical original). Each anchor
    must occur exactly once when its turn comes. edits receives {id, name, offset, before, after, ...}
    receipts and revert(result, edits) restores the input byte for byte.
    """
    hooks = hooks_for(path)
    if not hooks:
        return text
    original, applied = text, []
    for hook in hooks:
        need(text.count(hook.before) == 1, 'Anchor ' + hook.id + ' is not unique in ' + path)
        added = hook_blocks(hook)[1]
        need(added == '' or occurrences(text, added) == 0, 'Hook ' + hook.id + ' is already applied in ' + path)
        offset = text.index(hook.before)
        result = text[:offset] + hook.after + text[offset + len(hook.before):]
        applied.append(dict(id=hook.id, name=hook.name, offset=offset, before=hook.before, after=hook.after,
                            count=1, beforeSha256=sha(text), afterSha256=sha(result)))
        text = result
    need(revert(text, applied) == original, 'Inverse of the status-label hooks failed: ' + path)
    if edits is not None:
        edits.extend(applied)
    if output is not None:
        proof = dict(sourcePath=path, fixedCommit=COMMIT, beforeSha256=sha(original), afterSha256=sha(text),
                     edits=applied, inverseVerified=True, mainCompiled=False, runtimeExecuted=False)
        target = Path(output) / 'v041-video-status-label-hook-proofs' / ((output_name or Path(path).name) + '.json')
        wide(target).parent.mkdir(parents=True, exist_ok=True)
        wide(target).write_bytes((json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    return text


def apply_with(path, text, edit):
    """apply() routed through a generator's own edit(value, before, after, 1) receipt function."""
    edits = []
    projected = apply(path, text, edits=edits)
    value = text
    for entry in edits:
        value = edit(value, entry['before'], entry['after'], 1)
    need(value == projected, 'Generator edit replay differs from the reviewed hooks: ' + path)
    return value


def hook_blocks(hook):
    """Lines a hook removes and adds, as whole-line blocks (common line prefix and suffix stripped)."""
    old, new = hook.before.split('\n'), hook.after.split('\n')
    head = 0
    while head < min(len(old), len(new)) and old[head] == new[head]:
        head += 1
    tail = 0
    while tail < min(len(old), len(new)) - head and old[len(old) - 1 - tail] == new[len(new) - 1 - tail]:
        tail += 1
    removed, added = old[head:len(old) - tail], new[head:len(new) - tail]
    return ('\n'.join(removed) + '\n' if removed else ''), ('\n'.join(added) + '\n' if added else '')


def occurrences(text, block):
    return ('\n' + text).count('\n' + block)


def verify_hooks(repo):
    """Prove every hook against the canonical v025 text and the pinned v0.4.1 blobs; return receipts."""
    sources = verified_sources(repo)
    receipts = []
    for path in dict.fromkeys(hook.path for hook in HOOKS):
        canonical, latest = baseline(repo, path), sources[path]
        edits = []
        result = apply(path, canonical, edits=edits)
        need(revert(result, edits) == canonical, 'Hook inverse failed: ' + path)
        for hook, edit in zip(hooks_for(path), edits):
            removed, added = hook_blocks(hook)
            need(canonical.count(hook.before) == 1, 'Anchor ' + hook.id + ' is not unique in the canonical text')
            need(added == '' or occurrences(latest, added) >= 1, 'Added text of ' + hook.id + ' is not in v0.4.1')
            need(removed == '' or occurrences(latest, removed) == 0, 'Removed text of ' + hook.id + ' is still in v0.4.1')
            receipts.append(dict(
                id=hook.id, path=path, name=hook.name, consumer=hook.consumer, anchorCountCanonical=1,
                anchorCountAfterEarlierHooks=1, addedLines=added.count('\n'), removedLines=removed.count('\n'),
                addedBlockCountInV041=occurrences(latest, added) if added else 0,
                removedBlockCountInV041=occurrences(latest, removed) if removed else 0,
                afterVerbatimInV041=hook.after in latest, offset=edit['offset'],
                beforeSha256=sha(hook.before), afterSha256=sha(hook.after)))
        for needle in PARITY_NEEDLES.get(path, ()):
            need(result.count(needle) == latest.count(needle),
                 'Hooked ' + path + ' differs from v0.4.1 for ' + repr(needle))
        receipts.append(dict(id='result', path=path, baselineSha256LF=sha(canonical), hookedSha256LF=sha(result),
                             inverseRestoresBaseline=True,
                             needleCounts={needle: [result.count(needle), latest.count(needle)]
                                           for needle in PARITY_NEEDLES.get(path, ())}))
    return receipts


def package_path(path):
    return path[path.index('/java/') + len('/java/'):]


def label_order(text):
    marks = ['if (charging) add(', 'if (course) add(', 'if (cooperation) add(', 'if (interactive) add(', 'addAll(serverLabels']
    positions = [text.index(mark) for mark in marks]
    need(positions == sorted(positions), 'Status label priority order changed')
    return marks


def label_vocabulary(*texts):
    vocabulary = tuple(sorted({literal for text in texts for literal in re.findall(r'"([^"\n]+)"', text)}))
    need(vocabulary == LABEL_VOCABULARY, 'Status label vocabulary changed')
    return vocabulary


def emit_models(repo):
    """Return {path: (bytes, receipt)} for the six emitted main sources; nothing is written."""
    raw, sources = verified_raw(repo), verified_sources(repo)
    emitted = {}
    for path in (LABELS, BADGES):
        text = sources[path]
        need(text.encode('utf-8') == raw[path], 'Raw v0.4.1 source is not byte-exact: ' + path)
        emitted[path] = (raw[path], dict(mode='raw-v041', sha256=sha(raw[path]), bytes=len(raw[path]),
                                         identicalToArchive=True))
    order = label_order(sources[LABELS])
    vocabulary = label_vocabulary(sources[LABELS], sources[BADGES])
    for path in (LIST_MODELS, SPACE_MODELS):
        before = baseline(repo, path)
        hunks = reviewed_delta(path, WHOLE_MODEL_DELTAS, before, sources[path])
        emitted[path] = (raw[path], dict(
            mode='whole-v041-replacement', baselineSha256LF=sha(before), sha256=sha(raw[path]), bytes=len(raw[path]),
            identicalToArchive=True, baselineToV041Hunks=hunks, onlyReviewedStatusLabelHunks=True,
            inverseRestoresBaseline=True))
    for path in (SEARCH_MODELS, FAVORITE_MODELS):
        before, edits = baseline(repo, path), []
        text = apply(path, before, edits=edits)
        need(revert(text, edits) == before, 'Hook inverse failed: ' + path)
        remainder = reviewed_delta(path, REVIEWED_POST_HOOK_DELTAS, text, sources[path])
        # Full original fields now include search/all/v2 and WatchLaterData.ps.
        # The reviewed hook + remainder chain restores each whole v025 input.
        data = raw[path]
        receipt = dict(
            mode='whole-v041-replacement', hooks=[edit['id'] for edit in edits],
            baselineSha256LF=sha(before), sha256=sha(data), bytes=len(data),
            identicalToArchive=True, inverseRestoresBaseline=True,
            appliedPostHookUpstreamHunks=remainder, deferredUpstreamHunks=[])
        if path == SEARCH_MODELS:
            data, followup = search_up_followup.advance_model(repo, data)
            receipt.update(mode='whole-v043-search-up-author-replacement',
                archive=search_up_followup.ARCHIVE, upstreamCommit=search_up_followup.COMMIT,
                sha256=sha(data), bytes=len(data), modelFollowup=followup)
        emitted[path] = (data, receipt)
    for path, (data, receipt) in emitted.items():
        text, latest = data.decode('utf-8'), sources[path]
        needles = {}
        for needle in PARITY_NEEDLES.get(path, ()):
            need(text.count(needle) == latest.count(needle) and text.count(needle) > 0,
                 'Emitted ' + path + ' differs from v0.4.1 for ' + repr(needle))
            needles[needle] = text.count(needle)
        for needle in ABSENT_FROM_EMITTED.get(path, ()):
            need(text.count(needle) == 0 and latest.count(needle) > 0, 'Deferred upstream text leaked into ' + path)
        receipt['parityNeedleCounts'] = needles
        receipt['deferredAbsent'] = list(ABSENT_FROM_EMITTED.get(path, ()))
    emitted[LABELS][1].update(priorityOrder=order, vocabulary=list(vocabulary))
    return {package_path(path): value for path, value in emitted.items()}


def rewrite_test(path, text):
    """JUnit4 -> kotlin.test: only import lines change; every test body stays byte-identical."""
    rewritten, edits = text, []
    for before, after in TEST_IMPORTS:
        if before in rewritten:
            need(rewritten.count(before) == 1, 'Original test import is not unique: ' + before.strip())
            offset = rewritten.index(before)
            rewritten = rewritten[:offset] + after + rewritten[offset + len(before):]
            edits.append(dict(offset=offset, before=before, after=after))
    need('org.junit' not in rewritten, 'JUnit4 reference left in ' + path)
    need(text.count('    @Test\n') == TEST_CASES[path] == rewritten.count('    @Test\n'), 'Original test case count changed: ' + path)
    restored = rewritten
    for edit in reversed(edits):
        offset = edit['offset']
        need(restored[offset:offset + len(edit['after'])] == edit['after'], 'Test import inverse changed: ' + path)
        restored = restored[:offset] + edit['before'] + restored[offset + len(edit['after']):]
    need(restored == text, 'Complete original test inverse failed: ' + path)
    return rewritten, edits


def test_support(text):
    """durationSeconds and asVideoItem verbatim from the pinned UserContentRepository (tests only)."""
    need(text.count(SUPPORT_MARKER) == 1 and text.count(SUPPORT_DECLARATION) == 1, 'Original test support anchors changed')
    tail = text[text.index(SUPPORT_MARKER):]
    need(tail.endswith('\n') and tail.index(SUPPORT_MARKER) < tail.index(SUPPORT_DECLARATION), 'Original test support tail changed')
    return ('// Original source ' + USER_CONTENT + ' at v0.4.1 ' + COMMIT[:8] + ': test support, verbatim.\n'
            'package com.android.purebilibili.data.repository\n\n'
            'import com.android.purebilibili.data.model.response.*\n\n' + tail), tail


def emit_tests(repo):
    """Return {path: (bytes, receipt)} for the two original tests and their test-only support."""
    sources = verified_sources(repo)
    emitted = {}
    for path in (LABELS_TEST, COOPERATION_TEST):
        rewritten, edits = rewrite_test(path, sources[path])
        data = rewritten.encode('utf-8')
        emitted[package_path(path)] = (data, dict(
            mode='original-test-junit4-to-kotlin-test', source=path, originalSha256=sha(sources[path]),
            sha256=sha(data), cases=TEST_CASES[path], importEdits=[dict(before=e['before'].strip(), after=e['after'].strip())
                                                                    for e in edits], completeOriginalInverse=True))
    body, tail = test_support(sources[USER_CONTENT])
    data = body.encode('utf-8')
    emitted[package_path(SUPPORT_PATH)] = (data, dict(
        mode='verbatim-tail', source=USER_CONTENT, tailSha256=sha(tail), sha256=sha(data), declarations=['durationSeconds', 'asVideoItem']))
    followup_tests = search_up_followup.emit_tests(repo)
    need(not set(emitted).intersection(followup_tests), 'Duplicate UP-search test producer')
    emitted.update(followup_tests)
    return emitted


def write_all(root, files):
    root = Path(root).resolve()
    for relative, (data, _) in sorted(files.items()):
        target = (root / relative).resolve()
        need(target.is_relative_to(root), 'Generated source escapes its output: ' + relative)
        wide(target).parent.mkdir(parents=True, exist_ok=True)
        need(not wide(target).is_symlink(), 'Generated source is a link: ' + relative)
        wide(target).write_bytes(data)
        need(wide(target).read_bytes() == data, 'Generated source readback failed: ' + relative)


def generate(repo, output, tests_output):
    repo, main_root, test_root = Path(repo).resolve(), Path(output).resolve(), Path(tests_output).resolve()
    archives = [(repo / path).resolve() for path in (ARCHIVE, search_up_followup.ARCHIVE)]
    need(not (main_root.is_relative_to(test_root) or test_root.is_relative_to(main_root)), 'Main/test generated roots overlap')
    for root in (main_root, test_root):
        for archive in archives:
            need(not (root.is_relative_to(archive) or archive.is_relative_to(root)), 'Output overlaps fixed source')
    hooks = verify_hooks(repo)
    models, tests = emit_models(repo), emit_tests(repo)
    proof = dict(
        schemaVersion=1, upstreamCommit=COMMIT, baselineCommit=BASELINE_COMMIT, manifestSha256=MANIFEST_SHA256,
        sources=PINS, mainSources={name: receipt for name, (_, receipt) in sorted(models.items())},
        testSources={name: receipt for name, (_, receipt) in sorted(tests.items())}, hooks=hooks,
        decodeParity=dict(typedBadges='List<VideoContentBadge> as upstream; a non-object element fails the whole page',
                          chargingPay='any non-null level including 0 is charging, as in the upstream tests',
                          flexibleCooperation='FlexibleInt: JSON boolean true reads 0 for is_union_video and rights.is_cooperation'),
        upstreamScope=dict(
            subscribeLabelExists=False, pinnedLabelInSharedPolicy=False, pinnedChip='SpaceTopVideoCard badgeLabel only',
            paidLabelFromSearchSpaceOrWatchLater=False, fullV041ParityClaimed=False),
        mainCompiled=False, jvmTestsExecuted=False, runtimeExecuted=False)
    proof_text = json.dumps(proof, ensure_ascii=False, indent=2, sort_keys=True) + '\n'
    write_all(main_root, {**models, PROOF_NAME: (proof_text.encode('utf-8'), None)})
    write_all(test_root, tests)
    return dict(commit=COMMIT, mainSources=len(models), testSources=len(tests),
                hooks=sum(1 for row in hooks if row['id'] != 'result'), proofSha256=sha(proof_text))
