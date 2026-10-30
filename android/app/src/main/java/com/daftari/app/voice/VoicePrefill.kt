/*
 * دفتري · voice/VoicePrefill.kt
 * ما يُعبَّأ مسبقاً في نافذة المبلغ (من الصوت أو من «سداد كامل»): المبلغ، العملة، الملاحظة، والنص المسموع.
 * The pre-fill handed to the money sheet, encoded as one String so it survives rotation / process death in
 * rememberSaveable (like the sheet's own state). Pure Kotlin, unit-tested.
 */
package com.daftari.app.voice

import com.daftari.app.core.Currency

data class VoicePrefill(
    val amount: String = "",
    val currency: Currency? = null,
    val note: String = "",
    /** what was heard; "" when the pre-fill did not come from the voice («سداد كامل») */
    val transcript: String = "",
    /** what the sentence said the entry is, to warn when it differs from the sheet that is open */
    val kind: VoiceKind? = null,
) {
    fun encode(): String = listOf(amount, currency?.code.orEmpty(), note, transcript, kind?.name.orEmpty()).joinToString(SEP) { it.replace(SEP, " ") }

    companion object {
        private const val SEP = "\u001F"

        fun decode(s: String?): VoicePrefill? {
            if (s.isNullOrEmpty()) return null
            val p = s.split(SEP)
            if (p.size < 5) return null
            return VoicePrefill(
                amount = p[0], currency = Currency.entries.firstOrNull { it.code == p[1] }, note = p[2], transcript = p[3],
                kind = VoiceKind.entries.firstOrNull { it.name == p[4] },
            )
        }

        fun of(d: VoiceDraft) = VoicePrefill(d.amountText, d.currency, d.note, d.transcript, d.kind)
    }
}
