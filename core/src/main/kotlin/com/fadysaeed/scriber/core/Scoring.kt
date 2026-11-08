package com.fadysaeed.scriber.core

/** How well each engine did on a session Fady corrected and marked as right. */
object Scoring {
    data class EngineScore(val engine: String, val title: String, val wer: Wer.Result, val medicinesHeard: Int, val medicinesSaid: Int)

    /** Medicines named in [text], counted by generic name (brand and generic count as the same medicine). */
    fun medicineCounts(text: String, medicines: Medicines): Map<String, Int> {
        val words = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val out = HashMap<String, Int>()
        var i = 0
        while (i < words.size) {
            val m = medicines.find(words.subList(i, minOf(words.size, i + 3)))?.takeIf { it.index == 0 && it.exact || it.index == 0 && it.count > 1 }
            if (m != null) { out[m.generic] = (out[m.generic] ?: 0) + 1; i += m.count } else i++
        }
        return out
    }

    fun score(reference: String, runs: List<TranscriptRun>, medicines: Medicines): List<EngineScore> {
        val said = medicineCounts(reference, medicines)
        return runs.filter { it.status == RunStatus.DONE }.map { r ->
            val heard = medicineCounts(r.text, medicines)
            val caught = said.entries.sumOf { (g, n) -> minOf(n, heard[g] ?: 0) }
            EngineScore(r.engine, r.engineTitle, Wer.compute(reference, r.text), caught, said.values.sum())
        }
    }
}
