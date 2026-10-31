/*
 * دفتري · chat/NameSpan.kt
 * تحديد اسم الزبون في الجملة كاملاً (كل الكلمات حتى المبلغ أو الفعل) ومطابقته مع الزبائن الموجودين: الأطول أولاً، مع توحيد الهمزات والتاء والياء و«ال».
 * 1.1.8 (the «محمد اليتيم» fix). Given the normalized words of a sentence and which of them can be part of a name, finds
 * the name run and resolves it against the book:
 *   1. a customer whose whole name is the run (or its start), longest first («محمد اليتيم» beats «محمد»);
 *   2. the run is the start of one or more customers' names («محمد» → «محمد علي الضبيبي») — the old prefix / typo match;
 *   3. otherwise the whole run is a NEW customer («زبون جديد: محمد اليتيم؟»), with any account sharing a word offered.
 * Words of the run after the matched name are items only when the amount is said with «بـ» («محمد رز وسكر بـ5000»).
 * Pure Kotlin, unit-tested (Features118Test).
 */
package com.daftari.app.chat

object NameSpan {
    /** Longest name we take from one run (a 4-part Yemeni name + «ال…»). */
    const val MAX_WORDS = 5

    /** The run of name words and what it resolved to; [nameEnd] = first index after the name (rest of the run = items, «بـ» form only). */
    data class Hit(val run: IntRange, val match: NameMatch, val nameEnd: Int)

    /** Maximal runs of indices for which [nameLike] holds. */
    fun runs(size: Int, nameLike: (Int) -> Boolean): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < size) {
            if (nameLike(i)) {
                var j = i
                while (j < size && nameLike(j)) j++
                out += i until j
                i = j
            } else i++
        }
        return out
    }

    private fun stripAl(w: String) = if (w.startsWith("ال") && w.length > 3) w.substring(2) else w

    /** Two normalized words name the same thing: equal, «لمحمد»=«محمد», «اليتيم»=«يتيم». */
    fun same(a: String, b: String): Boolean =
        a == b || ArabicText.variants(a).any { it == b || stripAl(it) == stripAl(b) } || stripAl(a) == stripAl(b)

    private fun near(a: String, b: String) = same(a, b) || ArabicText.variants(a).any { ArabicText.similar(it, b) }

    /** How many words of [run] (from its start) spell the WHOLE name [nt]; 0 = they do not. Handles «عبد الله» = «عبدالله». */
    private fun cover(run: List<String>, nt: List<String>, fuzzy: Boolean): Int {
        // word by word
        if (run.size >= nt.size && nt.indices.all { k -> if (fuzzy) near(run[k], nt[k]) else same(run[k], nt[k]) }) return nt.size
        // joined spelling: «عبد الله» ↔ «عبدالله»
        val target = nt.joinToString("")
        var acc = ""
        for (j in run.indices) {
            acc += if (j == 0) (ArabicText.variants(run[0]).firstOrNull { target.startsWith(it) } ?: run[0]) else run[j]
            if (acc == target) return j + 1
            if (!target.startsWith(acc)) break
        }
        return 0
    }

    /**
     * Resolves the name in [w] over [run]. [baForm] = the amount is said with «بـ» right after the run, so words after a
     * known name are items. [skip] = indices that are not words of a name at all (amounts, verbs…), for the prefix match.
     */
    fun resolve(w: List<String>, run: IntRange, customers: List<Customer>, baForm: Boolean): Hit {
        val words = w.subList(run.first, run.last + 1).take(MAX_WORDS)
        val end = run.first + words.size
        val guessAll = words.joinToString(" ")
        // only accounts that start like the spoken name are offered («محمد …» for «محمد اليتيم», not «علي أحمد» for «العلي»)
        fun offer(l: List<Customer>) = l.filter { c -> ArabicText.words(c.name).firstOrNull()?.let { near(words[0], it) } == true }.take(6)

        // 1) whole customer names at the start of the run, longest first; exact spelling beats a typo
        for (fuzzy in listOf(false, true)) {
            val full = customers.mapNotNull { c -> val nt = ArabicText.words(c.name); if (nt.isEmpty()) null else cover(words, nt, fuzzy).takeIf { it > 0 }?.let { c to it } }
            if (full.isEmpty()) continue
            val best = full.maxOf { it.second }
            val tops = full.filter { it.second == best }.map { it.first }.distinctBy { it.id }
            val one = tops.singleOrNull()
            val ntSize = one?.let { ArabicText.words(it.name).size } ?: 1
            return when {
                // the run IS this name (or a longer one starting with a 2+ word name: «محمد اليتيم وسط العلي» → «محمد اليتيم»)
                one != null && (best == words.size || ntSize >= 2 || baForm) -> Hit(run, NameMatch.Found(one, run.first, run.first + best), run.first + best)
                one == null && (best == words.size || baForm) -> Hit(run, NameMatch.Ambiguous(tops, run.first, run.first + best), run.first + best)
                // «محمد» exists but he said «محمد اليتيم»: a new customer, «محمد» offered
                else -> Hit(run, NameMatch.NotFound(guessAll, offer(tops)), end)
            }
        }

        // 2) the run is the start of longer names («محمد» → «محمد علي الضبيبي»), with typos — the 1.1.0 matcher, inside the run only
        val outside = (w.indices).filter { it < run.first || it >= end }.toSet()
        when (val m = NameMatcher.match(w, customers, outside)) {
            is NameMatch.Found -> if (m.start == run.first && (m.end == end || baForm)) return Hit(run, m, m.end)
                else return Hit(run, NameMatch.NotFound(guessAll, offer(listOf(m.c))), end)
            is NameMatch.Ambiguous -> if (m.start == run.first && (m.end == end || baForm)) return Hit(run, m, m.end)
                else return Hit(run, NameMatch.NotFound(guessAll, offer(m.options)), end)
            else -> {}
        }

        // 3) nobody: the spoken name is new. With «بـ» only the first name (or «عبد الله» / «أبو علي») is the name, the rest items.
        if (baForm && words.size > 1) {
            val n = if (words[0] in setOf("عبد", "ابو", "ام", "بن", "بنت") && words.size > 2) 2 else 1
            return Hit(run, NameMatch.NotFound(words.take(n).joinToString(" ")), run.first + n)
        }
        return Hit(run, NameMatch.NotFound(guessAll), end)
    }
}
