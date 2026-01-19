package com.example.tonuinoaudiomanager

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.OutputStream

inline fun <T> ContentResolver.withSyncedOutputStream(
    targetUri: Uri,
    mode: String = "w",
    onUnavailable: () -> Throwable = { IllegalStateException("Stream unavailable") },
    block: (OutputStream) -> T
): T {
    val pfd = openFileDescriptor(targetUri, mode) ?: throw onUnavailable()
    val output = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    return try {
        val result = block(output)
        output.flush()
        pfd.fileDescriptor.sync()
        result
    } finally {
        output.close()
    }
}
