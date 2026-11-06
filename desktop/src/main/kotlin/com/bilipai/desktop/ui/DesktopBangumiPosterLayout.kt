package com.bilipai.desktop.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.dp

/** Keep the recommendation posters at the same minimum width as the index and search grids. */
internal fun desktopBangumiPosterGridCells(): GridCells = GridCells.Adaptive(112.dp)
