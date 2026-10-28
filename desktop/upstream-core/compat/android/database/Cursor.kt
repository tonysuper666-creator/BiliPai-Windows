package android.database

import java.io.File

/** Single-row cursor exposing the OpenableColumns of a local file. */
class Cursor private constructor(private val cols: List<String>, private val row: List<Any?>) : java.io.Closeable {
    private var pos = -1
    fun moveToFirst(): Boolean { pos = 0; return true }
    fun getColumnIndex(name: String): Int = cols.indexOf(name)
    fun isNull(i: Int): Boolean = row.getOrNull(i) == null
    fun getLong(i: Int): Long = (row[i] as Number).toLong()
    fun getString(i: Int): String? = row.getOrNull(i)?.toString()
    override fun close() {}
    companion object {
        fun ofFile(f: File) = Cursor(listOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), listOf(f.name, f.length()))
    }
}
