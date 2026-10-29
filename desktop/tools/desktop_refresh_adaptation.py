"""Desktop refresh invocation context, captured before a retained VM dispatches IO."""
def bind_refresh_launch(text):
    if '.launch' not in text:
        return text
    original = 'import kotlinx.coroutines.launch'
    replacement = 'import com.bilipai.desktop.ui.desktopRefreshLaunch as launch'
    if original in text:
        return text.replace(original, replacement)
    if 'import kotlinx.coroutines.*' in text:
        return text.replace('import kotlinx.coroutines.*', 'import kotlinx.coroutines.*\n' + replacement, 1)
    return text

def bind_refresh_lists(text):
    """Use the shared list state and content insets in desktop refresh pages."""
    hosts = ('AdaptivePullToRefreshBox(', 'ComfortablePullToRefreshBox(',
             'VideoCommentRefreshContainer(', 'fun DesktopOriginalDynamicCommentPanel(')
    if not any(host in text for host in hosts):
        return text
    text = text.replace('import androidx.compose.foundation.lazy.LazyColumn\n',
                        'import com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn\n')
    text = text.replace('import androidx.compose.foundation.lazy.grid.LazyVerticalGrid\n',
                        'import com.android.purebilibili.core.ui.components.AppLazyVerticalGrid as LazyVerticalGrid\n')
    if 'import androidx.compose.foundation.lazy.*' in text and 'LazyColumn(' in text:
        text = text.replace('import androidx.compose.foundation.lazy.*',
                            'import androidx.compose.foundation.lazy.*\nimport com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn', 1)
    return text
