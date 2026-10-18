package com.gruning.mimir.rewrite.core.metadata

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Reusable SAF open/read mechanics for cancellation-bound [ContentOpener] implementations. Uses a direct
 * unstable [ContentProviderClient.openFile] so the framework delivers [CancellationSignal] to the provider.
 */
internal object SafCancellableContentAccess {
    fun openReadOnlyDescriptor(
        resolver: ContentResolver,
        uri: Uri,
        scope: ContentCancellationScope,
    ): ParcelFileDescriptor? {
        scope.throwIfCancelled()
        val signal = CancellationSignal()
        val hook = scope.registerOnCancel { signal.cancel() }
        return try {
            val client = resolver.acquireUnstableContentProviderClient(uri) ?: return null
            try {
                client.openFile(uri, "r", signal)
            } finally {
                client.close()
            }
        } catch (error: Exception) {
            throw normalizeScopeFailure(scope, error)
        } finally {
            hook.close()
        }
    }

    fun openInputStream(
        resolver: ContentResolver,
        uri: Uri,
        scope: ContentCancellationScope,
    ): InputStream? {
        val descriptor = openReadOnlyDescriptor(resolver, uri, scope) ?: return null
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        val registration = scope.register(stream)
        return object : InputStream() {
            override fun read() = delegateRead { it.read() }

            override fun read(b: ByteArray) = delegateRead { it.read(b) }

            override fun read(b: ByteArray, off: Int, len: Int) = delegateRead { it.read(b, off, len) }

            override fun close() = registration.close()

            private inline fun <T> delegateRead(block: (InputStream) -> T): T {
                scope.throwIfCancelled()
                return try {
                    block(stream)
                } catch (error: Exception) {
                    throw normalizeScopeFailure(scope, error)
                }
            }
        }
    }

    fun openRandomAccess(
        resolver: ContentResolver,
        uri: Uri,
        scope: ContentCancellationScope,
    ): RandomAccessContent? {
        val descriptor = openReadOnlyDescriptor(resolver, uri, scope) ?: return null
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        val channel = try {
            stream.channel
        } catch (error: Exception) {
            stream.close()
            throw normalizeScopeFailure(scope, error)
        }
        val content = ChannelRandomAccessContent(channel)
        val registration = scope.register(
            Closeable {
                content.close()
                stream.close()
            },
        )
        return object : RandomAccessContent {
            override val length: Long
                get() {
                    scope.throwIfCancelled()
                    return try {
                        content.length
                    } catch (error: Exception) {
                        throw normalizeScopeFailure(scope, error)
                    }
                }

            override fun readAt(
                offset: Long,
                destination: ByteArray,
                destinationOffset: Int,
                byteCount: Int,
            ): Int {
                scope.throwIfCancelled()
                return try {
                    content.readAt(offset, destination, destinationOffset, byteCount)
                } catch (error: Exception) {
                    throw normalizeScopeFailure(scope, error)
                }
            }

            override fun close() = registration.close()
        }
    }

    fun openNativeSeekablePath(
        resolver: ContentResolver,
        uri: Uri,
        scope: ContentCancellationScope,
    ): NativeSeekablePath? {
        val descriptor = openReadOnlyDescriptor(resolver, uri, scope) ?: return null
        val seekable = try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            DescriptorNativeSeekablePath(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw normalizeScopeFailure(scope, error)
        }
        val registration = scope.register(seekable)
        return object : NativeSeekablePath {
            override val path: String
                get() {
                    scope.throwIfCancelled()
                    return seekable.path
                }

            override fun close() = registration.close()
        }
    }

    fun registerDirectInputStream(stream: InputStream, scope: ContentCancellationScope): InputStream {
        val registration = scope.register(stream)
        return object : InputStream() {
            override fun read() = delegateRead { stream.read() }

            override fun read(b: ByteArray) = delegateRead { stream.read(b) }

            override fun read(b: ByteArray, off: Int, len: Int) = delegateRead { stream.read(b, off, len) }

            override fun close() = registration.close()

            private inline fun <T> delegateRead(block: (InputStream) -> T): T {
                scope.throwIfCancelled()
                return try {
                    block(stream)
                } catch (error: Exception) {
                    throw normalizeScopeFailure(scope, error)
                }
            }
        }
    }

    fun registerDirectRandomAccess(
        content: RandomAccessContent,
        scope: ContentCancellationScope,
    ): RandomAccessContent {
        val registration = scope.register(content)
        return object : RandomAccessContent {
            override val length: Long
                get() {
                    scope.throwIfCancelled()
                    return try {
                        content.length
                    } catch (error: Exception) {
                        throw normalizeScopeFailure(scope, error)
                    }
                }

            override fun readAt(
                offset: Long,
                destination: ByteArray,
                destinationOffset: Int,
                byteCount: Int,
            ): Int {
                scope.throwIfCancelled()
                return try {
                    content.readAt(offset, destination, destinationOffset, byteCount)
                } catch (error: Exception) {
                    throw normalizeScopeFailure(scope, error)
                }
            }

            override fun close() = registration.close()
        }
    }

    fun registerDirectNativeSeekablePath(
        path: NativeSeekablePath,
        scope: ContentCancellationScope,
    ): NativeSeekablePath {
        val registration = scope.register(path)
        return object : NativeSeekablePath {
            override val path: String
                get() {
                    scope.throwIfCancelled()
                    return path.path
                }

            override fun close() = registration.close()
        }
    }

    fun normalizeScopeFailure(scope: ContentCancellationScope, error: Exception): Exception {
        if (error is CancellationException) throw error
        if (scope.isCancelled || error is OperationCanceledException) {
            throw CancellationException("content operation cancelled", error)
        }
        return error
    }

    private class ChannelRandomAccessContent(
        private val channel: FileChannel,
    ) : RandomAccessContent {
        override val length: Long = channel.size()

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            require(offset >= 0L)
            require(destinationOffset >= 0 && byteCount >= 0)
            require(destinationOffset <= destination.size - byteCount)
            if (byteCount == 0 || offset >= length) return 0
            val target = ByteBuffer.wrap(destination, destinationOffset, byteCount)
            var total = 0
            while (target.hasRemaining()) {
                val read = channel.read(target, offset + total)
                if (read < 0) break
                if (read == 0) break
                total += read
            }
            return total
        }

        override fun close() = channel.close()
    }

    private class DescriptorNativeSeekablePath(
        private val descriptor: ParcelFileDescriptor,
    ) : NativeSeekablePath {
        private val closed = AtomicBoolean(false)
        override val path: String = "/proc/self/fd/${descriptor.fd}"

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { descriptor.close() }
        }
    }
}
