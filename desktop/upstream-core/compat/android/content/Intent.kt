package android.content

import android.net.Uri

/** Request data only. Creating an Intent never opens a window or launches an application. */
class Intent(var action: String, var data: Uri? = null)
