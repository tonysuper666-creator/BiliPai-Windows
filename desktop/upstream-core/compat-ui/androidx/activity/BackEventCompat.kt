package androidx.activity

/** androidx.activity's back event data: where a back gesture is and how far it went. The desktop raises no gestures. */
class BackEventCompat(val touchX: Float, val touchY: Float, val progress: Float, val swipeEdge: Int) {
    companion object { const val EDGE_LEFT = 0; const val EDGE_RIGHT = 1; const val EDGE_NONE = 2 }
}
