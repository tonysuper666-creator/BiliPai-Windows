"""Exact original BlueSnow Compose body; platform resource/Canvas/clock ports only.

Called by the sole brand-motion producer after its fixed-source verification.
No resource copying, manager, network, cache or competing producer entry point.
"""
import hashlib
import re

BLUE_SNOW_LF_SHA256 = '459d914d7a32ef73cf87c13c2b3f0f05e42c5e03eff9078cfad89a1495fee1f3'
BLUE_SNOW_V034_LF_SHA256 = 'e4ff623fa5b859bdffe82f7723988cbdd2b0bd9f524acfd37d7bbe95a4a54171'


def adapt_blue_snow_with_recipe(raw: bytes):
    original = raw.decode('utf-8').replace('\r\n', '\n')
    source_sha = hashlib.sha256(original.encode('utf-8')).hexdigest()
    if source_sha not in (BLUE_SNOW_LF_SHA256, BLUE_SNOW_V034_LF_SHA256):
        raise ValueError('Fixed BlueSnow source mismatch')
    text = original
    recipe = []

    def change(old, new):
        nonlocal text
        if text.count(old) != 1:
            raise ValueError('BlueSnow mapping occurrence mismatch')
        offset = text.index(old)
        text = text.replace(old, new)
        recipe.append({'before': old, 'after': new, 'offset': offset, 'count': 1})

    change('import android.os.SystemClock\n', 'import com.bilipai.desktop.ui.DesktopHomeClock as SystemClock\n')
    for obsolete in ('import android.graphics.BitmapFactory\n', 'import androidx.annotation.RawRes\n',
                     'import androidx.annotation.DrawableRes\n', 'import androidx.compose.foundation.Image\n',
                     'import androidx.compose.ui.res.painterResource\n', 'import androidx.compose.ui.platform.LocalContext\n',
                     'import kotlinx.coroutines.Dispatchers\n', 'import kotlinx.coroutines.withContext\n'):
        change(obsolete, '')
    for old, new in (
        ('com.airbnb.lottie.compose.LottieAnimation', 'com.bilipai.desktop.brand.DesktopMaidMotionCanvas as LottieAnimation'),
        ('com.airbnb.lottie.compose.LottieCompositionSpec', 'com.bilipai.desktop.brand.DesktopMaidCompositionSpec as LottieCompositionSpec'),
        ('com.airbnb.lottie.compose.rememberLottieAnimatable', 'com.bilipai.desktop.brand.rememberDesktopMaidAnimatable as rememberLottieAnimatable'),
        ('com.airbnb.lottie.compose.rememberLottieComposition', 'com.bilipai.desktop.brand.rememberDesktopMaidComposition as rememberLottieComposition'),
    ):
        change('import ' + old + '\n', 'import ' + new + '\n')
    change('import com.android.bilipai.brandmotion.R\n',
           'import com.bilipai.desktop.brand.DesktopMaidAnimation\n'
           'import com.bilipai.desktop.brand.DesktopMaidStillImage\n'
           'import com.bilipai.desktop.brand.rememberDesktopMaidForeground\n'
           'import com.bilipai.desktop.ui.LocalDesktopHomePlatform\n')
    match = re.search(r'enum class MaidAnimation\([\s\S]*?\n}\n', text)
    if match is None:
        raise ValueError('Original Maid enum not found')
    change(match.group(), 'typealias MaidAnimation = DesktopMaidAnimation\n')
    change('    val resources = LocalContext.current.resources\n',
           '    val desktopForeground by rememberDesktopMaidForeground(LocalDesktopHomePlatform.current.background)\n')
    change('LottieCompositionSpec.RawRes(animation.resource)', 'LottieCompositionSpec.Animation(animation)')
    change('    val active = foreground && isVisible && inViewport\n',
           '    val active = foreground && desktopForeground && isVisible && inViewport\n')
    bitmap_prepare = '''                        withContext(Dispatchers.IO) {
                            val asset = requireNotNull(composition.images["maid_bitmap"])
                            require(asset.fileName == resources.getResourceEntryName(animation.staticResource) + ".png")
                            if (asset.bitmap == null) {
                                val bitmap = requireNotNull(BitmapFactory.decodeResource(
                                    resources,
                                    animation.staticResource,
                                    BitmapFactory.Options().apply { inScaled = false }
                                ))
                                require(bitmap.width == asset.width && bitmap.height == asset.height)
                                asset.bitmap = bitmap
                            }
                        }
'''
    prepared_indent = '                        '
    if source_sha == BLUE_SNOW_V034_LF_SHA256:
        bitmap_prepare = ''.join('    ' + line if line.strip() else line
                                 for line in bitmap_prepare.splitlines(keepends=True))
        prepared_indent += '    '
    change(bitmap_prepare, prepared_indent + 'composition.requirePreparedImage(animation)\n')
    change('''            Image(
                painter = painterResource(animation.staticResource),
                contentDescription = "蓝雪女仆",
                modifier = Modifier.fillMaxSize()
            )''', '''            DesktopMaidStillImage(
                result = REQUIRE_RESULT,
                contentDescription = "蓝雪女仆",
                modifier = Modifier.fillMaxSize()
            )'''.replace('REQUIRE_RESULT', 'requireNotNull(result)'
                         if source_sha == BLUE_SNOW_V034_LF_SHA256 else 'result'))
    if source_sha == BLUE_SNOW_V034_LF_SHA256:
        change('''            LottieAnimation(
                composition = player.composition,
''', '''            LottieAnimation(
                composition = player.composition,
                result = requireNotNull(result),
''')
    if reverse_blue_snow(text, recipe) != original:
        raise ValueError('BlueSnow full-source inverse mismatch')
    return text, recipe


def adapt_blue_snow(raw: bytes) -> str:
    return adapt_blue_snow_with_recipe(raw)[0]


def reverse_blue_snow(adapted: str, recipe):
    text = adapted
    for item in reversed(recipe):
        old, new, offset = item['before'], item['after'], item['offset']
        if text[offset:offset + len(new)] != new:
            raise ValueError('BlueSnow positioned inverse mismatch')
        text = text[:offset] + old + text[offset + len(new):]
    return text
