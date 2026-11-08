package com.fadysaeed.scriber.engines

import android.content.Context
import com.fadysaeed.scriber.core.Medicines
import com.fadysaeed.scriber.core.PersonalWords
import java.io.File

/**
 * Writes the two files Parakeet needs to lean towards medicine names: the names themselves, and
 * the word-piece list with scores (bpe.vocab) made from the model's own tokens.txt. Kept in the
 * app's private storage; they hold medicine names only, nothing about patients.
 */
object MedicineHints {
    const val SCORE = 1.0f

    fun prepare(context: Context, parakeetDir: File, personal: PersonalWords, score: Float = SCORE): Speech.Hints {
        val dir = File(context.noBackupFilesDir, "hints").apply { mkdirs() }
        val vocab = File(dir, "bpe.vocab")
        val tokens = File(parakeetDir, "tokens.txt")
        if (!vocab.isFile || vocab.lastModified() < tokens.lastModified()) {
            vocab.writeText(bpeVocab(tokens.readLines()))
        }
        val hot = File(dir, "hotwords.txt")
        hot.writeText(Medicines.bundledNames().plus(personal.words).filter { n -> n.all { it.isLetter() || it == ' ' || it == '-' } }.distinct().sorted().joinToString("\n") + "\n")
        return Speech.Hints(hot, vocab, score)
    }

    /** "piece id" lines become "piece<TAB>score", earlier pieces scoring higher; special tokens are left out. */
    fun bpeVocab(lines: List<String>): String = buildString {
        for (line in lines) {
            val cut = line.lastIndexOf(' ')
            if (cut <= 0) continue
            val piece = line.substring(0, cut)
            val id = line.substring(cut + 1).toIntOrNull() ?: continue
            if (piece.startsWith("<") && piece.endsWith(">")) continue
            append(piece).append('\t').append(-id.toFloat()).append('\n')
        }
    }
}
