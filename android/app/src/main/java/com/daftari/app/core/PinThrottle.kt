package com.daftari.app.core

/**
 * 1.1.1: wrong-PIN lockout that survives closing and reopening the lock screen (and the app): 5 wrong codes → 5 minutes locked.
 * The counters live in [store] (SharedPreferences on the phone), not in the screen's memory.
 */
class PinThrottle(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {
    interface Store { fun getLong(key: String): Long; fun putLong(key: String, value: Long) }

    companion object {
        const val MAX_TRIES = 5
        const val LOCK_MS = 5 * 60_000L
        private const val K_FAILS = "pin_fails"
        private const val K_UNTIL = "pin_locked_until"
    }

    /** ms left on the lock, 0 when not locked. A lock "from the future" (clock moved back) is capped at [LOCK_MS]. */
    fun remainingMs(): Long {
        val until = store.getLong(K_UNTIL)
        if (until <= 0) return 0
        val left = until - now()
        if (left <= 0) { store.putLong(K_UNTIL, 0); store.putLong(K_FAILS, 0); return 0 }
        return minOf(left, LOCK_MS)
    }

    /** 1.1.4: a new password (set after Google re-verification) clears the lockout. */
    fun reset() { store.putLong(K_FAILS, 0); store.putLong(K_UNTIL, 0) }

    fun triesLeft(): Int = (MAX_TRIES - store.getLong(K_FAILS).toInt()).coerceIn(0, MAX_TRIES)

    sealed interface Result { data object Ok : Result; data class Wrong(val left: Int) : Result; data class Locked(val ms: Long) : Result }

    /** Checks [verify] unless locked; records the outcome. */
    fun attempt(verify: () -> Boolean): Result {
        remainingMs().let { if (it > 0) return Result.Locked(it) }
        if (verify()) { store.putLong(K_FAILS, 0); store.putLong(K_UNTIL, 0); return Result.Ok }
        val fails = store.getLong(K_FAILS) + 1
        store.putLong(K_FAILS, fails)
        if (fails >= MAX_TRIES) { store.putLong(K_UNTIL, now() + LOCK_MS); return Result.Locked(LOCK_MS) }
        return Result.Wrong((MAX_TRIES - fails).toInt())
    }

    /** «الرمز خاطئ (بقيت محاولتان)» / «تم إيقاف المحاولات… انتظر 4 دقائق». */
    fun message(r: Result): String? = when (r) {
        Result.Ok -> null
        is Result.Wrong -> "الرمز غير صحيح" + if (r.left <= 3) " (${tries(r.left)})" else ""
        is Result.Locked -> lockedText(r.ms)
    }

    fun lockedText(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).toInt()
        val m = when (min) { 1 -> "دقيقة واحدة"; 2 -> "دقيقتين"; in 3..10 -> "$min دقائق"; else -> "$min دقيقة" }
        return "أُوقفت المحاولات بعد $MAX_TRIES محاولات خاطئة. حاول بعد $m."
    }

    private fun tries(n: Int) = when (n) { 1 -> "بقيت محاولة واحدة"; 2 -> "بقيت محاولتان"; else -> "بقيت $n محاولات" }
}
