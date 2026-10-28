package com.daftari.app.domain

import java.time.LocalDate

/** Who registered: a shop owner tracking customers' debts, or a market landlord tracking shop tenants. */
enum class Role(val labelAr: String) {
    SHOP_OWNER("صاحب محل"),
    LANDLORD("صاحب سوق / مؤجر");

    val partyLabel: String get() = if (this == LANDLORD) "المحلات" else "الزبائن"
    val partySingular: String get() = if (this == LANDLORD) "محل" else "زبون"
}

enum class PartyKind { CUSTOMER, SHOP }

enum class Period(val labelAr: String) {
    NONE("بدون مبلغ دوري"),
    DAILY("يومي"),
    WEEKLY("أسبوعي"),
    MONTHLY("شهري"),
}

enum class EntryType { CHARGE, PAYMENT }

enum class Status(val labelAr: String) {
    PAID("مسدد"),
    PARTIAL("جزئي"),
    OWES("عليه"),
    LATE("متأخر"),
}

data class Party(
    val id: Long = 0,
    val kind: PartyKind,
    val shopNumber: String = "",
    val name: String,
    val phone: String = "",
    val activity: String = "",
    val periodicAmount: Long = 0,
    val period: Period = Period.NONE,
    val startDate: LocalDate = LocalDate.now(),
    val notes: String = "",
    val location: String = "",
    /** متأخرات سابقة: what was owed before the app (e.g. imported from a paper sheet). */
    val openingBalance: Long = 0,
    /** عدد المحلات for this tenant (a tenant may rent several shops). */
    val unitsCount: Int = 1,
    /** حد الدين for a customer (0 = no limit). */
    val creditLimit: Long = 0,
    /** تصنيف (e.g. «الدور الأول»، «زبائن الحارة») — empty = عام. */
    val category: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val title: String get() = if (kind == PartyKind.SHOP && shopNumber.isNotBlank()) "محل $shopNumber" else name
    val badge: String get() = if (kind == PartyKind.SHOP && shopNumber.isNotBlank()) shopNumber else name.trim().take(1)
}

data class Entry(
    val id: Long = 0,
    val partyId: Long,
    val type: EntryType,
    val amount: Long,
    val date: LocalDate,
    val note: String = "",
    /** اسم المحصل */
    val collector: String = "",
    /** رقم السند */
    val receiptNo: String = "",
    /** optional purchased items for a debt, one per line («2 كيس رز، زيت») */
    val items: String = "",
    /** when the customer promised to pay this debt (null = none) */
    val dueDate: LocalDate? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class Attachment(
    val id: Long = 0,
    val partyId: Long,
    val fileName: String,
    val displayName: String,
    val mime: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A party with its computed figures, as every list shows it. */
data class PartySummary(
    val party: Party,
    val balance: Long,
    val totalDue: Long,
    val totalPaid: Long,
    val status: Status,
    val daysLate: Int,
    val lastPayment: LocalDate?,
    val paidToday: Long,
)

data class StatementLine(
    val date: LocalDate,
    val description: String,
    val debit: Long,
    val credit: Long,
    val balance: Long,
)

data class Statement(
    val party: Party,
    val from: LocalDate,
    val to: LocalDate,
    val opening: Long,
    val lines: List<StatementLine>,
    val totalDebit: Long,
    val totalCredit: Long,
    val closing: Long,
)
