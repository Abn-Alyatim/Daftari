/*
 * دفتري · voice/VoiceHint.kt
 * تلميح للتعرّف على الكلام: عبارات المحل («سجل عليه»، «سدد الي عليه كامل»، «طلع لي كشف»…) وأسماء الزبائن الأكثر نشاطاً،
 * يُرسل مع الصوت إلى الخادم (Whisper من Groq: prompt) وإلى النموذج دون إنترنت (initial_prompt).
 * 1.1.8. Whisper spells what it was primed with: a name in the hint («محمد اليتيم») is heard as that name instead of
 * «وسط العلي». Pure builder (unit-tested); [names] is kept fresh by the view model (top 50 by recent activity). Optional
 * both ways: an old server ignores the hint, and an empty hint is not sent.
 */
package com.daftari.app.voice

object VoiceHint {
    /** Madyan's phrases first, so a cut drops names, never these. */
    val PHRASES = listOf(
        "سجل عليه", "سجل على", "سدد الي عليه كامل", "سدد كاملاً", "اخذ بـ", "عليه", "دفع", "سدد",
        "طلع لي كشف", "نزل لي كشف", "اطبع لي كشف", "خرج لي كشف", "خلّيه عليه", "قضّى", "صفّى حسابه", "ألف", "ألفين", "مية", "ريال", "سعودي", "دولار",
    )
    /** ≈200 Whisper tokens of Arabic for the server (it caps at 400 characters as well). */
    const val ONLINE_MAX_CHARS = 400
    /** A shorter hint for the small phone model: a long prompt slows it and can be echoed back on a quiet take. */
    const val OFFLINE_MAX_CHARS = 220
    const val MAX_NAMES = 50

    /** Customer names, most recently active first (set by the view model whenever the book changes). */
    @Volatile var names: List<String> = emptyList()

    /** "عبارات. الزبائن: أ، ب، …" cut to [maxChars] at a whole name. */
    fun build(names: List<String>, maxChars: Int): String {
        val head = PHRASES.joinToString("، ") + "."
        val sb = StringBuilder(head)
        val clean = names.map { it.replace(Regex("[\\s\\u00A0]+"), " ").trim() }.filter { it.isNotBlank() }.distinct().take(MAX_NAMES)
        var first = true
        for (n in clean) {
            val piece = (if (first) " الزبائن: " else "، ") + n
            if (sb.length + piece.length > maxChars) break
            sb.append(piece); first = false
        }
        return if (sb.length > maxChars) sb.substring(0, maxChars) else sb.toString()
    }

    fun online(names: List<String>): String = build(names, ONLINE_MAX_CHARS)
    fun offline(names: List<String>): String = build(names.take(20), OFFLINE_MAX_CHARS)

    /** Names ordered by their latest activity (entry date, newest first), then the rest — pure. */
    fun byRecent(names: Map<Long, String>, lastActivity: Map<Long, Long>): List<String> =
        names.entries.sortedWith(compareByDescending<Map.Entry<Long, String>> { lastActivity[it.key] ?: Long.MIN_VALUE }.thenBy { it.value })
            .map { it.value }.take(MAX_NAMES)
}
