package com.verdictzero.darkhall.core.raw

/** A file that is not a raw Darkhall can read, or one that is damaged. The message is shown to the user. */
class RawFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
