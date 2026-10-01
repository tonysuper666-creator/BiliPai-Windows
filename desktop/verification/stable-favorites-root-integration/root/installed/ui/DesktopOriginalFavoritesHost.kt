package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.data.model.response.FavoriteSearchScope
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.feature.space.SeasonSeriesDetailViewModel

/** Root retains the sole page environment/scope by account epoch and route. Never mount
 * its old PersonalFavorites CommunityFeed beside this original VM. */
@Composable
fun DesktopOriginalFavoritesHost(
    environment:DesktopFavoriteEnvironment,
    bindings:DesktopFavoriteBindings,
    onBack:()->Unit,
    onVideoClick:(String,Long,String,Boolean)->Unit,
    onUpClick:((Long)->Unit)?,
    onCollectionClick:(FavoriteCollectionRoute)->Unit,
    onFolderClick:(Long,Long,String,String)->Unit,
    onBangumi:(Long)->Unit,
    onArticle:(Long,String)->Unit,
    onTopic:(Long)->Unit,
    onCourse:(Long)->Unit,
    onWeb:(String,String)->Unit,
    onPlayAllAudio:(String,Long)->Unit,
    initialSearchQuery:String="",
    initialSearchScope:FavoriteSearchScope=FavoriteSearchScope.CURRENT_FOLDER,
    initialSubscribed:Boolean=false,
    detail:FavoriteCollectionRoute?=null,
    listScopedSearchChannel:kotlinx.coroutines.channels.Channel<String>?=null,
    scrollToTopChannel:kotlinx.coroutines.channels.Channel<Unit>?=null,
    isSearchDestination:Boolean=false,
    onOpenSearchDestination:((String)->Unit)?=null,
    isCurrentPage:Boolean=true,
    retainedViewModel:BaseListViewModel?=null,
    loadFavoriteViewModelOnEnter:Boolean=true,
) {
    if (!environment.isOwned()) return
    val viewModel=retainedViewModel ?: remember(environment,detail) {
        if(detail==null) FavoriteViewModel(environment)
        else SeasonSeriesDetailViewModel(environment).also{it.init(detail.type,detail.id,detail.mid,detail.title,detail.ownerName)}
    }
    LaunchedEffect(viewModel) {if(loadFavoriteViewModelOnEnter && viewModel is FavoriteViewModel) viewModel.loadData()}
    val window=LocalWindowSizeClass.current
    CompositionLocalProvider(LocalDesktopFavoriteBindings provides bindings,
        LocalDesktopFavoriteViewport provides DesktopFavoriteViewport(window.widthDp.value.toInt(),window.heightDp.value.toInt())) {
        CommonListScreen(viewModel,onBack,onVideoClick,onUpClick,onCollectionClick,onFolderClick,
            onFavoriteBangumiClick=onBangumi,onFavoriteCheeseClick=onCourse,onFavoriteArticleClick=onArticle,
            onFavoriteTopicClick=onTopic,onFavoriteWebClick=onWeb,initialSearchQuery=initialSearchQuery,
            initialFavoriteSearchScope=initialSearchScope,initialFavoriteSubscribed=initialSubscribed,
            onPlayAllAudioClick=onPlayAllAudio,favoriteCollectionSharedElementRoute=detail,
            listScopedSearchChannel=listScopedSearchChannel,scrollToTopChannel=scrollToTopChannel,
            isSearchDestination=isSearchDestination,onOpenSearchDestination=onOpenSearchDestination,
            isCurrentPage=isCurrentPage)
    }
}
