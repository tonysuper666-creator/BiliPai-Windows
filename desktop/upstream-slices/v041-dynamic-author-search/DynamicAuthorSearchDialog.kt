package com.android.purebilibili.feature.dynamic.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.AppLazyColumn
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.components.AppTextField
import com.android.purebilibili.core.ui.components.PageAwareAsyncImage
import com.android.purebilibili.feature.dynamic.SidebarUser
import com.android.purebilibili.feature.dynamic.searchDynamicAuthors
import com.android.purebilibili.feature.home.components.BottomBarMatchedReusableLiquidDock
import com.android.purebilibili.feature.home.components.resolveFloatingDockGeometryScale

@Composable
internal fun DynamicAuthorSearchDialog(
    users: List<SidebarUser>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onAuthorSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(users, query) { searchDynamicAuthors(users, query) }
    val listState = remember(query) { LazyListState() }
    val liquidGlassEnabled = LocalAppThemeConfig.current.liquidGlassEnabled
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { AppText("搜索 UP 主") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Glass owns both the background and the transparent field surface;
                // disabled MD3/Miuix continue through the original adaptive text field.
                if (liquidGlassEnabled) {
                    val shape = AppShapes.container(ContainerLevel.Pill)
                    BottomBarMatchedReusableLiquidDock(
                        shape = shape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        reuseEnabled = true,
                        shellLensIntensity = resolveFloatingDockGeometryScale(56f),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { AppText("输入关注的 UP 主名字", maxLines = 1) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = shape,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = AppContentColors.primaryText(),
                                unfocusedTextColor = AppContentColors.primaryText(),
                                cursorColor = AppContentColors.accent(),
                            ),
                        )
                    }
                } else {
                    AppTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "输入关注的 UP 主名字",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isLoading) AppText("正在加载关注列表…")
                if (matches.isEmpty() && !isLoading) {
                    AppText(if (query.isBlank()) "暂无可搜索的 UP 主" else "没有找到匹配的 UP 主")
                }
                AppLazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(matches, key = { it.uid }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAuthorSelected(user.uid) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PageAwareAsyncImage(
                                model = user.face,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                            )
                            AppText(
                                text = user.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { AppTextButton(onClick = onDismiss) { AppText("关闭") } },
    )
}
