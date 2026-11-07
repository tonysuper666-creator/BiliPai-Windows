package com.bilipai.desktop.player.cache

import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopMediaByteSpanStoreTest {
    private val id = "a".repeat(64)

    private fun write(store: DesktopMediaByteSpanStore, start: Long = 0, size: Int = 700) {
        val reservation = store.reserve(size.toLong())
        try {
            store.begin(reservation, id, start, size.toLong(), true).use { it.write(ByteArray(size) { 42 }) }
            store.publish(reservation, id, start, size.toLong()) { it() }
        } catch (failure: Throwable) { store.discard(reservation); throw failure }
    }

    @Test fun evictionAndClearHaveOneDeletionOwner() = raceCleanup { it.clear() }

    @Test fun concurrentReservationsHaveOneDeletionOwner() = raceCleanup {
        val reservation = it.reserve(1)
        it.discard(reservation)
    }

    private fun raceCleanup(cleanup: (DesktopMediaByteSpanStore) -> Unit) = fixture { root, io ->
        val store = DesktopMediaByteSpanStore(root, 1024)
        write(store)
        val bytes = store.stats().first
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val paused = AtomicBoolean()
        // Pause after existingPathWithoutLinks has validated the victim, immediately
        // before deleteGarbage asks whether it is still a regular file. All IO is real.
        io.afterRealPath = { path, options ->
            if (path.toString().endsWith(".span") && options.isEmpty() && paused.compareAndSet(false, true)) {
                entered.countDown()
                check(resume.await(10, TimeUnit.SECONDS)) { "deletion owner was never released" }
            }
        }
        try {
            val writer = executor.submit<DesktopMediaByteSpanStore.Reservation> { store.reserve(700) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "eviction did not reach validation")
            // A second cleanup sees the same garbage while the first owns its deletion.
            cleanup(store)
            val chargedWhileDeleting = store.stats().first
            resume.countDown()
            val reservation = writer.get(10, TimeUnit.SECONDS)
            assertEquals(bytes, chargedWhileDeleting, "in-flight deletion must stay charged")
            assertEquals(828L to 0, store.stats())
            assertEquals(1, io.deletes.get(), "only the owner may delete this path")
            store.discard(reservation)
            store.clear(); store.clear()
            assertEquals(0L to 0, store.stats())
            write(store)
            store.open(id, 0)!!.use { reader ->
                val buffer = ByteArray(700)
                assertEquals(700, reader.read(buffer, 0, buffer.size))
                assertTrue(buffer.all { it == 42.toByte() })
            }
            assertTrue(store.stats().first <= store.limit)
            store.clear()
            assertEquals(0L to 0, store.stats())
        } finally {
            resume.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            store.close()
        }
    }

    @Test fun failedDeletionRemainsChargedAndCanBeRetried() = fixture { root, io ->
        val store = DesktopMediaByteSpanStore(root, 1024)
        write(store)
        val bytes = store.stats().first
        io.failDelete.set(true)
        store.clear()
        assertEquals(bytes to 0, store.stats())
        assertFailsWith<IOException> { store.reserve(800) }
        assertEquals(bytes to 0, store.stats())
        io.failDelete.set(false)
        write(store)
        assertTrue(store.stats().first <= store.limit)
        store.clear(); store.clear()
        assertEquals(0L to 0, store.stats())
    }

    @Test fun activeReaderPinsBytesAndPendingReservationBlocksIdleClear() = fixture { root, _ ->
        val store = DesktopMediaByteSpanStore(root, 1024)
        write(store)
        store.open(id, 0)!!.use { reader ->
            val before = store.stats()
            assertFailsWith<IOException> { store.reserve(700) }
            assertFailsWith<IllegalArgumentException> { store.clearIdle {} }
            assertEquals(before, store.stats())
            assertEquals(1, reader.read(ByteArray(1), 0, 1))
        }
        val reservation = store.reserve(700)
        assertFailsWith<IllegalArgumentException> { store.clearIdle {} }
        store.discard(reservation); store.discard(reservation)
        store.clearIdle {}
        assertEquals(0L to 0, store.stats())
    }

    @Test fun invalidGarbageFileStillFailsValidationAndKeepsItsCharge() = fixture { root, _ ->
        val store = DesktopMediaByteSpanStore(root, 1024)
        write(store)
        val bytes = store.stats().first
        val path = Files.list(root).use { it.findFirst().orElseThrow() }
        Files.delete(path); Files.createDirectory(path)
        assertFailsWith<IllegalArgumentException> { store.clear() }
        assertEquals(bytes to 0, store.stats())
        Files.delete(path)
        store.clear()
        assertEquals(0L to 0, store.stats())
    }

    private fun fixture(test: (Path, PausingFileSystem) -> Unit) {
        val root = Files.createTempDirectory("span-store-").toRealPath()
        val io = PausingFileSystem(root.fileSystem)
        try { test(io.wrap(root), io) }
        finally { root.toFile().deleteRecursively() }
    }
}

/** Delegates to the host filesystem; only scheduling and injected IO errors differ.
 * The same test runs against the unmodified production store, without reflection or hooks. */
private class PausingFileSystem(private val host: FileSystem) : FileSystem() {
    var afterRealPath: (Path, Array<out LinkOption>) -> Unit = { _, _ -> }
    val deletes = AtomicInteger()
    val failDelete = AtomicBoolean()
    fun wrap(path: Path): Path = PausingPath(path)
    private fun unwrap(path: Path): Path = (path as PausingPath).delegate
    private val io = object : FileSystemProvider() {
        override fun getScheme() = host.provider().scheme
        override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = error("unused")
        override fun getFileSystem(uri: URI): FileSystem = this@PausingFileSystem
        override fun getPath(uri: URI): Path = wrap(host.provider().getPath(uri))
        override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            Files.newByteChannel(unwrap(path), options, *attrs)
        override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
            val stream = Files.newDirectoryStream(unwrap(dir)) { filter.accept(wrap(it)) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = stream.map { wrap(it) }.toMutableList().iterator()
                override fun close() = stream.close()
            }
        }
        override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) { Files.createDirectory(unwrap(dir), *attrs) }
        override fun delete(path: Path) { Files.delete(unwrap(path)) }
        override fun deleteIfExists(path: Path): Boolean {
            if (path.toString().endsWith(".span")) {
                if (failDelete.get()) throw IOException("injected sharing violation")
                deletes.incrementAndGet()
            }
            return Files.deleteIfExists(unwrap(path))
        }
        override fun copy(source: Path, target: Path, vararg options: CopyOption) { Files.copy(unwrap(source), unwrap(target), *options) }
        override fun move(source: Path, target: Path, vararg options: CopyOption) { Files.move(unwrap(source), unwrap(target), *options) }
        override fun isSameFile(path: Path, path2: Path) = Files.isSameFile(unwrap(path), unwrap(path2))
        override fun isHidden(path: Path) = Files.isHidden(unwrap(path))
        override fun getFileStore(path: Path): FileStore = Files.getFileStore(unwrap(path))
        override fun checkAccess(path: Path, vararg modes: AccessMode) = host.provider().checkAccess(unwrap(path), *modes)
        override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? =
            Files.getFileAttributeView(unwrap(path), type, *options)
        override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A =
            Files.readAttributes(unwrap(path), type, *options)
        override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
            Files.readAttributes(unwrap(path), attributes, *options)
        override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) {
            Files.setAttribute(unwrap(path), attribute, value, *options)
        }
    }
    override fun provider(): FileSystemProvider = io
    override fun close() = Unit
    override fun isOpen() = host.isOpen
    override fun isReadOnly() = host.isReadOnly
    override fun getSeparator(): String = host.separator
    override fun getRootDirectories(): Iterable<Path> = host.rootDirectories.map(::wrap)
    override fun getFileStores(): Iterable<FileStore> = host.fileStores
    override fun supportedFileAttributeViews(): MutableSet<String> = host.supportedFileAttributeViews()
    override fun getPath(first: String, vararg more: String): Path = wrap(host.getPath(first, *more))
    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        PathMatcher { host.getPathMatcher(syntaxAndPattern).matches(unwrap(it)) }
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = host.userPrincipalLookupService
    override fun newWatchService(): WatchService = host.newWatchService()
    private inner class PausingPath(val delegate: Path) : Path by delegate {
        override fun toFile(): java.io.File = delegate.toFile()
        override fun getFileSystem(): FileSystem = this@PausingFileSystem
        override fun toAbsolutePath(): Path = wrap(delegate.toAbsolutePath())
        override fun normalize(): Path = wrap(delegate.normalize())
        override fun getParent(): Path? = delegate.parent?.let(::wrap)
        override fun resolve(other: String): Path = wrap(delegate.resolve(other))
        override fun resolve(other: Path): Path = wrap(delegate.resolve(unwrap(other)))
        override fun toRealPath(vararg options: LinkOption): Path {
            val real = delegate.toRealPath(*options)
            afterRealPath(real, options)
            return wrap(real)
        }
        override fun equals(other: Any?): Boolean = other is PausingPath && delegate == other.delegate
        override fun hashCode(): Int = delegate.hashCode()
        override fun toString(): String = delegate.toString()
    }
}
