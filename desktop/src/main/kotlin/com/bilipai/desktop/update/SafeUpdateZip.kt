package com.bilipai.desktop.update

import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

/** Extract only regular files into a new empty staging directory. The running installation is untouched. */
internal object SafeUpdateZip {
    const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$", RegexOption.IGNORE_CASE)

    internal fun safeRelativeName(raw: String): String {
        require(raw.isNotEmpty() && '\u0000' !in raw) { "更新压缩包包含空文件名" }
        val name = raw.replace('\\', '/').removeSuffix("/")
        require(name.isNotEmpty() && !name.startsWith('/') && ':' !in name) { "更新压缩包包含绝对路径" }
        val parts = name.split('/')
        require(parts.all { part ->
            part.isNotEmpty() && part != "." && part != ".." && !part.endsWith('.') && !part.endsWith(' ') &&
                !reserved.matches(part) && part.none { it.code < 32 || it in "<>\"|?*" }
        }) { "更新压缩包包含不安全路径: $raw" }
        return parts.joinToString("/")
    }

    fun extract(zip: Path, destination: Path, executable: String, checkCancelled: () -> Unit = {}): Path {
        require(!Files.isSymbolicLink(destination) && Files.isDirectory(destination) && Files.list(destination).use { !it.findAny().isPresent }) {
            "更新必须解压到全新的空目录"
        }
        require(safeRelativeName(executable) == executable && '/' !in executable) { "更新启动文件名无效" }
        val centralNames = inspectCentralDirectory(zip)
        val root = destination.toAbsolutePath().normalize()
        var expanded = 0L
        val extractedNames = mutableSetOf<String>()
        ZipFile(zip.toFile(), Charset.forName("CP437")).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                checkCancelled()
                val entry = entries.nextElement()
                require(entry.name in centralNames) { "压缩包文件目录不一致" }
                val relative = safeRelativeName(entry.name)
                val key = relative.lowercase(Locale.ROOT)
                require(extractedNames.add(key)) { "更新压缩包包含重名文件" }
                val target = root.resolve(relative).normalize()
                require(target.startsWith(root) && target != root) { "更新文件越过安装目录" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                require(entry.size in 0..MAX_ENTRY_BYTES) { "更新文件大小异常" }
                Files.createDirectories(target.parent)
                var entryBytes = 0L
                archive.getInputStream(entry).use { input ->
                    Files.newOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            expanded += count
                            require(entryBytes <= MAX_ENTRY_BYTES && expanded <= MAX_EXPANDED_BYTES) { "更新压缩包解压大小超限" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(entryBytes == entry.size) { "更新文件长度校验失败" }
            }
        }
        require(extractedNames.size == centralNames.size) { "更新压缩包文件计数不一致" }
        val executables = Files.walk(root, 5).use { paths ->
            paths.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) && it.fileName.toString().equals(executable, ignoreCase = true) }
                .toList()
        }
        require(executables.size == 1) { "更新压缩包必须包含唯一的 $executable" }
        return executables.single()
    }

    private fun inspectCentralDirectory(zip: Path): Set<String> {
        RandomAccessFile(zip.toFile(), "r").use { file ->
            val length = file.length()
            require(length >= 22) { "更新压缩包不完整" }
            val tailLength = minOf(length, 65_557).toInt()
            val tail = ByteArray(tailLength)
            file.seek(length - tailLength)
            file.readFully(tail)
            var end = -1
            for (index in tail.size - 22 downTo 0) {
                if (u32(tail, index) == 0x06054b50L && index + 22 + u16(tail, index + 20) == tail.size) {
                    end = index
                    break
                }
            }
            require(end >= 0) { "更新压缩包缺少文件目录" }
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "不支持分卷更新压缩包" }
            val count = u16(tail, end + 10)
            require(count in 1..MAX_ENTRIES && u16(tail, end + 8) == count) { "更新压缩包文件数量异常" }
            val size = u32(tail, end + 12)
            val offset = u32(tail, end + 16)
            val endOffset = length - tailLength + end
            require(offset + size <= endOffset && offset != 0xffffffffL && size != 0xffffffffL) { "不支持异常或 ZIP64 更新压缩包" }
            file.seek(offset)
            val names = mutableSetOf<String>()
            val normalizedNames = mutableSetOf<String>()
            repeat(count) {
                val header = ByteArray(46)
                file.readFully(header)
                require(u32(header, 0) == 0x02014b50L) { "更新压缩包目录损坏" }
                val flags = u16(header, 8)
                require(flags and 1 == 0) { "不支持加密更新压缩包" }
                val externalAttributes = u32(header, 38)
                val unixType = ((externalAttributes ushr 16) and 0xf000).toInt()
                require(unixType != 0xa000 && unixType !in setOf(0x1000, 0x2000, 0x6000, 0xc000)) {
                    "更新压缩包不能包含链接或特殊设备文件"
                }
                val nameBytes = ByteArray(u16(header, 28))
                file.readFully(nameBytes)
                val name = String(nameBytes, if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("CP437"))
                val relative = safeRelativeName(name)
                require(names.add(name) && normalizedNames.add(relative.lowercase(Locale.ROOT))) { "更新压缩包包含重名路径" }
                file.seek(file.filePointer + u16(header, 30) + u16(header, 32))
                require(file.filePointer <= offset + size) { "更新压缩包目录越界" }
            }
            require(file.filePointer == offset + size) { "更新压缩包目录长度异常" }
            return names
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
}
