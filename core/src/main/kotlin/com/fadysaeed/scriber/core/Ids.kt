package com.fadysaeed.scriber.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun defaultTitle(mode: SessionMode, atMillis: Long): String =
        (if (mode == SessionMode.DICTATION) "Dictation " else "Session ") +
            SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(Date(atMillis))

    fun fileStem(title: String): String = title.replace(Regex("""[^\p{L}\p{N} _-]"""), "").trim().replace(Regex("""\s+"""), "-").ifBlank { "scriber" }
}
