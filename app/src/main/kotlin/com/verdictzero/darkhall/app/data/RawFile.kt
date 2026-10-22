package com.verdictzero.darkhall.app.data

import android.net.Uri
import java.security.MessageDigest

/** A raw file the library knows about. [key] is stable across sessions and used for caches and edits. */
data class RawFile(val uri: Uri, val name: String, val size: Long, val modified: Long) {
    val key: String by lazy {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("$name|$size".toByteArray())
        md.digest().joinToString("") { "%02x".format(it) }
    }
    val baseName: String get() = name.substringBeforeLast('.')

    companion object {
        fun isRawName(name: String) = name.endsWith(".orf", ignoreCase = true)
    }
}
