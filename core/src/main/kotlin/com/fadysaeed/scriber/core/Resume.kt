package com.fadysaeed.scriber.core

/** What to do with a partly downloaded file. */
sealed interface ResumeAction {
    /** No usable partial file: download from the first byte. */
    data object StartFresh : ResumeAction

    /** Ask the server for the rest, from [offset]. */
    data class ResumeFrom(val offset: Long) : ResumeAction

    /** The partial file already has every byte; only the checksum is left to do. */
    data object AlreadyComplete : ResumeAction
}

object Resume {
    fun plan(partialBytes: Long, expectedSize: Long): ResumeAction = when {
        partialBytes <= 0L -> ResumeAction.StartFresh
        partialBytes == expectedSize -> ResumeAction.AlreadyComplete
        partialBytes > expectedSize -> ResumeAction.StartFresh
        else -> ResumeAction.ResumeFrom(partialBytes)
    }

    /** First byte position in a Content-Range header such as "bytes 100-999/1000", or null. */
    fun contentRangeStart(header: String?): Long? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""").find(header ?: return null) ?: return null
        return m.groupValues[1].toLongOrNull()
    }
}
