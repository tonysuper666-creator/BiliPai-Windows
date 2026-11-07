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

_LIST_IMPORTS = (
    ('import androidx.compose.foundation.lazy.LazyColumn\n',
     'import com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn\n'),
    ('import androidx.compose.foundation.lazy.grid.LazyVerticalGrid\n',
     'import com.android.purebilibili.core.ui.components.AppLazyVerticalGrid as LazyVerticalGrid\n'),
)
_STAR_IMPORT = 'import androidx.compose.foundation.lazy.*'
_STAR_ALIAS = '\nimport com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn'


def unbind_refresh_lists(text):
    """Exact inverse of bind_refresh_lists for one emitted body.

    Producers apply the binding after their stage receipts are recorded, so a
    receipt describes the body before these import lines. The result is
    accepted only when binding it again reproduces the emitted body exactly.
    """
    result = text
    if (_STAR_IMPORT + _STAR_ALIAS) in result:
        result = result.replace(_STAR_IMPORT + _STAR_ALIAS, _STAR_IMPORT, 1)
    for original, shared in _LIST_IMPORTS:
        result = result.replace(shared, original)
    if bind_refresh_lists(result) != text:
        raise ValueError('Refresh list binding is not exactly invertible')
    return result

_LAUNCH_ORIGINAL = 'import kotlinx.coroutines.launch'
_LAUNCH_SHARED = 'import com.bilipai.desktop.ui.desktopRefreshLaunch as launch'
_LAUNCH_STAR = 'import kotlinx.coroutines.*'

def unbind_refresh_launch(text):
    """Exact inverse of bind_refresh_launch for one emitted body.

    Accepted only when binding it again reproduces the emitted body exactly.
    """
    if (_LAUNCH_STAR + '\n' + _LAUNCH_SHARED) in text:
        result = text.replace(_LAUNCH_STAR + '\n' + _LAUNCH_SHARED, _LAUNCH_STAR, 1)
    else:
        result = text.replace(_LAUNCH_SHARED, _LAUNCH_ORIGINAL)
    if bind_refresh_launch(result) != text:
        raise ValueError('Refresh launch binding is not exactly invertible')
    return result
