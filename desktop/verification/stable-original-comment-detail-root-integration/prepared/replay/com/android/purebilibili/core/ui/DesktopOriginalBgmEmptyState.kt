package com.android.purebilibili.core.ui
import com.android.purebilibili.core.ui.components.AppText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
object LottieUrls {
    //  通用状态动画
    const val SUCCESS = "https://assets4.lottiefiles.com/packages/lf20_jbrw3hcz.json"
    const val ERROR = "https://assets1.lottiefiles.com/packages/lf20_cr9slsdh.json"
    const val EMPTY = "https://raw.githubusercontent.com/DrKLO/Telegram/master/TMessagesProj/src/main/res/raw/utyan_empty2.json"

    //  新手引导页面动画
    const val THEME_COLORS = "https://assets5.lottiefiles.com/packages/lf20_jtbfg2nb.json"  // 彩虹渐变
    const val VIDEO_PLAY = "https://assets8.lottiefiles.com/packages/lf20_khzniaya.json"  // 播放按钮
}

@Composable
fun EmptyState(
    message: String = "暂无内容",
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    //  [彩蛋] 是否启用点击彩蛋
    enableEasterEgg: Boolean = true
) {
    //  点击计数器触发彩蛋
    var clickCount by remember { mutableIntStateOf(0) }
    var easterEggMessage by remember { mutableStateOf<String?>(null) }
    
    //  点击彩蛋消息列表
    val easterEggMessages = remember {
        listOf(
            "别戳我啦～ 😆",
            "我只是个空状态... 🥺",
            "再点也不会有内容的！",
            "你在找什么？🔍",
            "好无聊啊～ 去看点视频吧！",
            "点点点，你可真会点！",
            "咚咚咚！有人在家吗？🚪"
        )
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacingTokens.DoubleExtraLarge)
            .then(
                if (enableEasterEgg) {
                    Modifier.clickable {
                        clickCount++
                        if (clickCount >= 3) {
                            easterEggMessage = easterEggMessages.random()
                        }
                        if (clickCount >= 7) {
                            clickCount = 0  // 重置
                        }
                    }
                } else Modifier
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        com.bilipai.desktop.ui.DesktopBgmLottie(
            url = LottieUrls.EMPTY,
            size = 150.dp
        )
        Spacer(modifier = Modifier.height(AppSpacingTokens.Large))
        
        //  显示彩蛋消息或默认消息（使用柔和的主题色）
        AppText(
            text = easterEggMessage ?: message,
            style = MaterialTheme.typography.bodyLarge,
            color = if (easterEggMessage != null) 
                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            else 
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(AppSpacingTokens.Medium))
            AppText(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

@Composable
fun CutePersonLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
    size: Dp? = null,
) {
    AdaptiveLoadingIndicator(
        modifier = modifier,
        size = size,
        color = color,
        strokeWidth = strokeWidth,
    )
}
