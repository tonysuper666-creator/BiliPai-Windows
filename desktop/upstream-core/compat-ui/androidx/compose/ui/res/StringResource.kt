package androidx.compose.ui.res

import android.content.res.Resources
import androidx.compose.runtime.Composable

/** Desktop stringResource over the generated upstream string tables, in the system language (see [Resources]). */
@Composable
fun stringResource(id: Int): String = Resources.system.getString(id)

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String = Resources.system.getString(id, *formatArgs)
