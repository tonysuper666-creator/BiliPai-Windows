package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.video.ui.components.FavoriteFolderSheet
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalFavoriteFolderSession

/** Consumes the original transient flows rather than inventing selected IDs at the UI boundary. */
@Composable
fun DesktopOriginalFavoriteFolderDrawer(session:DesktopOriginalFavoriteFolderSession) {
    val visible by session.favoriteFolderDialogVisible.collectAsState()
    val folders by session.favoriteFolders.collectAsState()
    val loading by session.isFavoriteFoldersLoading.collectAsState()
    val selected by session.favoriteSelectedFolderIds.collectAsState()
    val saving by session.isSavingFavoriteFolders.collectAsState()
    if(visible) FavoriteFolderSheet(
        folders=folders,isLoading=loading,selectedFolderIds=selected,isSaving=saving,
        onFolderToggle=session::toggleFavoriteFolderSelection,
        onSaveClick=session::saveFavoriteFolderSelection,
        onDismissRequest=session::dismissFavoriteFolderDialog,
        onCreateFolder=session::createFavoriteFolder,
    )
}
