package com.example.tonuinoaudiomanager

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.OutputStream

const val SYNCED_OUTPUT_BUFFER_SIZE = 256 * 1024

inline fun <T> ContentResolver.withSyncedOutputStream(
    targetUri: Uri,
    mode: String = "w",
    onUnavailable: () -> Throwable = { IllegalStateException("Stream unavailable") },
    block: (OutputStream) -> T
): T {
    val pfd = openFileDescriptor(targetUri, mode) ?: throw onUnavailable()
    val rawOutput = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    val output = BufferedOutputStream(rawOutput, SYNCED_OUTPUT_BUFFER_SIZE)
    return try {
        val result = block(output)
        output.flush()
        pfd.fileDescriptor.sync()
        result
    } finally {
        output.close()
    }
}
