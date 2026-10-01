package com.android.purebilibili.feature.space
import com.android.purebilibili.data.model.response.*
sealed class SpaceUiState {
    object Loading : SpaceUiState()
    data class Success(
        val userInfo: SpaceUserInfo,
        val relationStat: RelationStatData? = null,
        val upStat: UpStatData? = null,
        // 头部充电/大航海摘要（App 端 /x/v2/space，失败时为 null，行不显示）
        val chargeGroup: SpaceSupporterGroup? = null,
        val guardGroup: SpaceSupporterGroup? = null,
        val videos: List<SpaceVideoItem> = emptyList(),
        val totalVideos: Int = 0,
        val isLoadingMore: Boolean = false,
        val hasMoreVideos: Boolean = true,
        val videoPageLoadCompletionVersion: Long = 0L,
        val lastVideoPageLoadFailed: Boolean = false,
        //  视频分类
        val categories: List<SpaceVideoCategory> = emptyList(),
        val selectedTid: Int = 0,  // 0 表示全部
        //  视频排序
        val sortOrder: VideoSortOrder = VideoSortOrder.PUBDATE,
        //  合集和系列
        val seasons: List<SeasonItem> = emptyList(),
        val series: List<SeriesItem> = emptyList(),
        val seasonArchives: Map<Long, List<SeasonArchiveItem>> = emptyMap(),  // season_id -> videos
        val seriesArchives: Map<Long, List<SeriesArchiveItem>> = emptyMap(),   // series_id -> videos
        val createdFavoriteFolders: List<FavFolder> = emptyList(),
        val collectedFavoriteFolders: List<FavFolder> = emptyList(),
        //  主页 Tab
        val topVideo: SpaceTopArcData? = null,
        val notice: String = "",
        val homeFavoriteFolders: List<FavFolder> = emptyList(),
        val homeFavoriteFolderCount: Int = 0,
        val homeCoinVideos: List<SpaceAggregateArchiveItem> = emptyList(),
        val homeCoinVideoCount: Int = 0,
        val homeLikeVideos: List<SpaceAggregateArchiveItem> = emptyList(),
        val homeLikeVideoCount: Int = 0,
        val homeBangumiItems: List<SpaceAggregateArchiveItem> = emptyList(),
        val homeBangumiCount: Int = 0,
        val homeComicItems: List<SpaceAggregateArchiveItem> = emptyList(),
        val homeComicCount: Int = 0,
        val bangumiItems: List<FollowBangumiItem> = emptyList(),
        val bangumiTotal: Int = 0,
        val bangumiPage: Int = 1,
        val isLoadingBangumi: Boolean = false,
        val hasMoreBangumi: Boolean = true,
        //  动态 Tab
        val dynamics: List<SpaceDynamicItem> = emptyList(),
        val dynamicOffset: String = "",
        val hasMoreDynamics: Boolean = true,
        val isLoadingDynamics: Boolean = false,
        val hasLoadedDynamicsOnce: Boolean = false,
        val lastDynamicLoadFailed: Boolean = false,
        //  课堂 Tab
        val cheeseItems: List<SpaceCheeseItem> = emptyList(),
        val cheesePage: Int = 1,
        val isLoadingCheese: Boolean = false,
        val hasMoreCheese: Boolean = true,
        val hasLoadedCheeseOnce: Boolean = false,
        val lastCheeseLoadFailed: Boolean = false,
        val hasCheeseTab: Boolean = false,
        
        //  Uploads Sub-Tab
        val selectedSubTab: SpaceSubTab = SpaceSubTab.VIDEO,
        val selectedContributionTabId: String = createSpaceContributionTabId(param = "video"),
        val contributionTabs: List<SpaceContributionTab> = buildDefaultSpaceContributionTabs(),
        val audios: List<SpaceAudioItem> = emptyList(),
        val articles: List<SpaceArticleItem> = emptyList(),
        val totalAudios: Int = 0,
        val totalArticles: Int = 0,
        val audioPage: Int = 1,
        val articlePage: Int = 1,
        val articleOffset: String = "",
        val isLoadingAudios: Boolean = false,
        val isLoadingArticles: Boolean = false,
        val hasMoreAudios: Boolean = true,
        val hasMoreArticles: Boolean = true,
        val watchProgressByBvid: Map<String, SpaceWatchProgress> = emptyMap(),
        val lastWatchedVideo: SpaceWatchProgress? = null,
        val pendingLocateBvid: String? = null,
        val locateMessage: String? = null,
        val isSearchMode: Boolean = false,
        val searchQuery: String = "",
        val headerState: SpaceHeaderState = SpaceHeaderState(null, null, null, null, "", emptyList(), emptyList()),
        val tabShellState: SpaceTabShellState = buildInitialTabShellState(),
        val mainTabs: List<SpaceMainTabItem> = buildDefaultSpaceMainTabs()
    ) : SpaceUiState()
    data class Error(val message: String) : SpaceUiState()
}

