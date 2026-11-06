package com.bilipai.desktop.data

import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider

/** Test-only stand-in for an NTFS short name: lexical SHORT~1 paths address real
 * files, and toRealPath expands them. No link flags, attributes, or DAO behavior
 * are mocked; all I/O and NOFOLLOW checks go to the host filesystem. */
internal class CanonicalAliasFileSystem(private val target: Path) : FileSystem() {
    private val host = target.fileSystem
    private val alias = target.resolveSibling("SHORT~1")
    val root: Path = AliasPath(alias)
    private val io = AliasProvider()

    init { require(!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) }

    private fun actual(path: Path): Path {
        val lexical = (path as? AliasPath)?.lexical ?: return path
        return if (lexical.startsWith(alias)) target.resolve(alias.relativize(lexical)) else lexical
    }
    private fun wrap(path: Path): Path = AliasPath(path)

    private inner class AliasPath(val lexical: Path) : Path by lexical {
        override fun getFileSystem(): FileSystem = this@CanonicalAliasFileSystem
        override fun getRoot(): Path? = lexical.root?.let(::wrap)
        override fun getParent(): Path? = lexical.parent?.let(::wrap)
        override fun resolve(other: String): Path = wrap(lexical.resolve(other))
        override fun resolve(other: Path): Path = wrap(lexical.resolve((other as? AliasPath)?.lexical ?: other))
        override fun normalize(): Path = wrap(lexical.normalize())
        override fun toAbsolutePath(): Path = wrap(lexical.toAbsolutePath())
        override fun toRealPath(vararg options: LinkOption): Path = actual(this).toRealPath(*options)
        override fun equals(other: Any?): Boolean = other is AliasPath &&
            other.fileSystem === fileSystem && lexical == other.lexical
        override fun hashCode(): Int = 31 * System.identityHashCode(fileSystem) + lexical.hashCode()
        override fun toString(): String = lexical.toString()
    }

    private inner class AliasProvider : FileSystemProvider() {
        private val delegate = host.provider()
        override fun getScheme(): String = "canonical-alias"
        override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = unsupported()
        override fun getFileSystem(uri: URI): FileSystem = this@CanonicalAliasFileSystem
        override fun getPath(uri: URI): Path = unsupported()
        override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            delegate.newByteChannel(actual(path), options, *attrs)
        override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = unsupported()
        override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = delegate.createDirectory(actual(dir), *attrs)
        override fun delete(path: Path) = delegate.delete(actual(path))
        override fun copy(source: Path, target: Path, vararg options: CopyOption) = delegate.copy(actual(source), actual(target), *options)
        override fun move(source: Path, target: Path, vararg options: CopyOption) = delegate.move(actual(source), actual(target), *options)
        override fun isSameFile(path: Path, path2: Path): Boolean = delegate.isSameFile(actual(path), actual(path2))
        override fun isHidden(path: Path): Boolean = delegate.isHidden(actual(path))
        override fun getFileStore(path: Path): FileStore = delegate.getFileStore(actual(path))
        override fun checkAccess(path: Path, vararg modes: AccessMode) = delegate.checkAccess(actual(path), *modes)
        override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? =
            delegate.getFileAttributeView(actual(path), type, *options)
        override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A =
            delegate.readAttributes(actual(path), type, *options)
        override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
            delegate.readAttributes(actual(path), attributes, *options)
        override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) =
            delegate.setAttribute(actual(path), attribute, value, *options)
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not used by the DAO alias fixture")
    override fun provider(): FileSystemProvider = io
    override fun close() = Unit
    override fun isOpen(): Boolean = true
    override fun isReadOnly(): Boolean = host.isReadOnly
    override fun getSeparator(): String = host.separator
    override fun getRootDirectories(): Iterable<Path> = host.rootDirectories.map(::wrap)
    override fun getFileStores(): Iterable<FileStore> = host.fileStores
    override fun supportedFileAttributeViews(): MutableSet<String> = host.supportedFileAttributeViews()
    override fun getPath(first: String, vararg more: String): Path = wrap(host.getPath(first, *more))
    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher = unsupported()
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = host.userPrincipalLookupService
    override fun newWatchService(): WatchService = unsupported()
}
