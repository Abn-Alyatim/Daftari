/*
 * دفتري · core/QuickEntry.kt
 * أدوات التسجيل السريع (1.1.6): مبالغ جاهزة للسداد بضغطة، وآخر خمسة زبائن تعاملت معهم لاختيار الاسم بضغطة واحدة.
 * Quick-entry helpers: the one-tap payment amounts and the «recent customers» list (last 5 accounts that had an
 * operation, newest first, from the real ledger — nothing invented). Pure Kotlin, unit-tested.
 */
package com.daftari.app.core

import com.daftari.app.domain.Entry

object QuickEntry {
    /** One-tap amounts on the payment sheet, in whole units of the account's currency. */
    val PAY_CHIPS = listOf(500L, 1000L, 5000L, 10000L)

    /** Accounts with the newest operations first (by insertion order), distinct, only those still in the book. */
    fun recentPartyIds(entries: List<Entry>, alive: Set<Long>, n: Int = 5): List<Long> =
        entries.sortedWith(compareByDescending<Entry> { it.createdAt }.thenByDescending { it.id })
            .map { it.partyId }.filter { it in alive }.distinct().take(n)

    /** The full remaining balance as the amount field's text («12500», «99.5»); "" when nothing is owed. */
    fun fullBalanceText(balanceMinor: Long): String =
        if (balanceMinor <= 0) "" else java.math.BigDecimal(balanceMinor).movePointLeft(2).stripTrailingZeros().toPlainString()
}
