/*
 * دفتري · chat/OfflineAssistant.kt
 * «مدين» دون إنترنت: يحوّل الأمر المفهوم إلى إجابة أو بطاقة تأكيد — لا يكتب شيئاً بنفسه.
 * Turns a [Command] into an answer or a confirmation card ([ChatAction]); nothing is written here. 1.1.8: «سدد الي عليه
 * كامل» fills the customer's whole balance, «سجل عليه» without an amount asks «كم؟» for the last customer, a new
 * customer's card says «زبون جديد: …؟» and offers accounts with part of the name, «طلع لي كشف» opens the statement PDF.
 */
package com.daftari.app.chat

import com.daftari.app.audit.AuditRecord
import com.daftari.app.backup.BackupData
import com.daftari.app.core.Currency
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.core.Rates
import com.daftari.app.core.cur
import com.daftari.app.data.ChatAction
import com.daftari.app.data.ChatTable
import com.daftari.app.data.Dashboard
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.PartySummary
import com.daftari.app.domain.Role
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «مدين» without internet: turns a [Command] into an answer, or into a confirmation card. Nothing is written here — every
 * change comes back as a [ChatAction] the user confirms first.
 */
object OfflineAssistant {
    data class Ctx(
        val d: Dashboard, val entries: List<Entry>, val role: Role, val currency: Currency, val rates: Rates, val today: LocalDate,
        val lastLog: AuditRecord? = null, val openedBackup: BackupData? = null, val now: LocalDateTime = LocalDateTime.now(),
        /** 1.1.8 chat memory: the customer the chat was last about, and a pending «كم المبلغ؟» */
        val last: Customer? = null, val pending: ChatMemory.Pending? = null,
    )

    private fun m(v: Long, c: Currency) = Money.format(v, c)
    private fun noun(r: Role) = if (r == Role.LANDLORD) "المستأجر" else "الزبون"

    fun customers(d: Dashboard) = d.summaries.map { Customer(it.party.id, it.party.name, it.party.phone) }

    /** Null = not understood offline (the caller may ask the online AI or show help). */
    fun respond(text: String, x: Ctx): ChatMessage? {
        backupAnswer(text, x)?.let { return it }
        val cmd = Nlu.parse(text, customers(x.d), x.today, x.now, x.last, x.pending)
        val about = Nlu.mentioned(cmd)?.id ?: 0L
        return answer(cmd, x, text)?.let { m -> if (m.about == 0L && about > 0) m.copy(about = about) else m }
    }

    private fun answer(cmd: Command, x: Ctx, text: String): ChatMessage? {
        return when (cmd) {
            is Command.AddDebt -> money(cmd.who, cmd.amount, true, cmd.items, cmd.date, x, text)
            is Command.AddPayment -> money(cmd.who, cmd.amount, false, "", cmd.date, x, text, cmd.method, cmd.transferNo, cmd.transferCo, cmd.senderPhone, cmd.full, cmd.half)
            is Command.Balance -> withParty(cmd.who, x, "balance") { s -> balance(s, x) }
            is Command.Statement -> withParty(cmd.who, x, "statement") { s -> statement(s, x).let { m -> if (cmd.pdf) m.copy(text = m.text + "\nفتحتُ الكشف لتطبعه أو ترسله PDF 📄", openStatement = s.party.id) else m } }
            is Command.TopDebtors -> top(cmd.n, x)
            Command.Today -> today(x)
            Command.TotalOwed -> ChatMessage(false, "إجمالي المتبقي على الكل: ${m(x.d.totalOwed, x.currency)}\nعدد من عليهم ديون: ${x.d.summaries.count { it.balance > 0 }}، منهم ${x.d.lateCount} متأخر.")
            is Command.CreateCustomer -> createCustomer(cmd, x)
            is Command.Reminder -> reminder(cmd, x)
            Command.Undo -> undo(x)
            Command.Help -> ChatMessage(false, HELP)
            Command.Unknown -> null
        }
    }

    const val HELP = "أعمل دون إنترنت 👌 جرّب:\n• سجّل على محمد رز وسكر بـ5000\n• سجّل عليه 500 (على آخر زبون ذكرته)\n• محمد سدّد الي عليه كامل\n• طلّع لي كشف محمد (PDF)\n• محمد سدّد 2000 (أو 100 سعودي حوالة رقم 123456)\n• محمد سدّد 5000 جوالي رقم 778899\n• كم على محمد؟ / كشف حساب محمد\n• من الأكثر ديناً؟ / ملخص اليوم\n• أضف زبوناً جديداً اسمه ياسر 771234567\n• ذكّرني بمطالبة محمد غداً الساعة 10\n• تراجع (يلغي آخر عملية)"

    private fun summaryOf(id: Long, x: Ctx) = x.d.summaries.firstOrNull { it.party.id == id }

    private fun withParty(who: NameMatch?, x: Ctx, kind: String, f: (PartySummary) -> ChatMessage): ChatMessage = when (who) {
        is NameMatch.Found -> summaryOf(who.c.id, x)?.let(f) ?: ChatMessage(false, "لم أجد الحساب")
        is NameMatch.Ambiguous -> ChatMessage(false, "أيّهم تقصد؟", choices = who.options.map { ChatAction(type = kind, party = it.name, partyId = it.id, offline = true) })
        is NameMatch.NotFound -> if (who.suggestions.isEmpty()) ChatMessage(false, "لم أجد «${who.guess}» في دفترك.")
            else ChatMessage(false, "لم أجد «${who.guess}» في دفترك. هل تقصد:", choices = who.suggestions.map { ChatAction(type = kind, party = it.name, partyId = it.id, offline = true) })
        null -> ChatMessage(false, "من تقصد؟ اكتب الاسم، مثل: ${if (kind == "statement") "طلع لي كشف محمد" else "كم على محمد؟"}")
    }

    fun balance(s: PartySummary, x: Ctx): ChatMessage {
        val c = s.party.cur(x.currency)
        val last = s.lastPayment?.let { "\nآخر سداد: ${Dates.short(it)}" }.orEmpty()
        return ChatMessage(false, "على ${s.party.name}: ${m(s.balance, c)}\nإجمالي ما أخذه ${m(s.totalDue, c)}، وسدّد ${m(s.totalPaid, c)}.$last", about = s.party.id)
    }

    fun statement(s: PartySummary, x: Ctx): ChatMessage {
        val c = s.party.cur(x.currency)
        val st = Ledger.statement(s.party, x.entries.filter { it.partyId == s.party.id }, LocalDate.of(2000, 1, 1), x.today)
        val rows = st.lines.takeLast(40).map { l -> listOf(Dates.numeric(l.date), l.description, if (l.debit > 0) Money.plain(l.debit) else "", if (l.credit > 0) Money.plain(l.credit) else "", Money.plain(l.balance)) }
        return ChatMessage(false, "كشف حساب ${s.party.name} — الرصيد ${m(st.closing, c)}", about = s.party.id, table = ChatTable("كشف ${s.party.name}", listOf("التاريخ", "البيان", "عليه", "له", "الرصيد"), rows))
    }

    private fun top(n: Int, x: Ctx): ChatMessage {
        val top = x.d.summaries.filter { it.balance > 0 }.sortedByDescending { it.balance }.take(n)
        if (top.isEmpty()) return ChatMessage(false, "لا يوجد أحد عليه دين 👏")
        return ChatMessage(false, "الأكثر عليهم:", ChatTable("الأكثر عليهم", listOf("م", "الاسم", "المتبقي", "الحالة"),
            top.mapIndexed { i, s -> listOf((i + 1).toString(), s.party.name, Money.plain(s.balance, s.party.cur(x.currency)), s.status.labelAr) }))
    }

    private fun today(x: Ctx): ChatMessage {
        val t = x.entries.filter { it.date == x.today }
        val paid = t.filter { it.type == EntryType.PAYMENT }; val debts = t.filter { it.type == EntryType.CHARGE }
        return ChatMessage(false, "ملخص اليوم ${Dates.short(x.today)}:\n• تحصيل: ${m(x.d.collectedToday, x.currency)} (${paid.size} دفعة)\n• ديون جديدة: ${m(x.d.newDebtToday, x.currency)} (${debts.size} عملية)\n• إجمالي المتبقي على الكل: ${m(x.d.totalOwed, x.currency)}")
    }

    private val curOf = mapOf(Cur.YER to Currency.YER, Cur.SAR to Currency.SAR, Cur.USD to Currency.USD)

    private fun money(who: NameMatch?, amount: AmountHit?, debt: Boolean, items: String, date: LocalDate, x: Ctx, text: String,
                      method: String = "", transferNo: String = "", transferCo: String = "", senderPhone: String = "", full: Boolean = false, half: Boolean = false): ChatMessage {
        val word = if (debt) "دين" else "سداد"
        if (amount == null && !((full || half) && !debt)) {
            val f = who as? NameMatch.Found
            return when {
                // 1.1.8 «سجل عليه»: ask how much for that customer; a bare amount next answers it ([ChatMemory.pending])
                f != null -> ChatMessage(false, if (debt) "كم أسجّل على ${f.c.name}؟ اكتب المبلغ، مثل: سجل عليه 500" else "كم سدّد ${f.c.name}؟ اكتب المبلغ، مثل: 2000 (أو: سدد الي عليه كامل)",
                    about = f.c.id, askAmountPayment = !debt)
                who == null -> ChatMessage(false, if (debt) "على من؟ اكتب الاسم والمبلغ، مثل: سجل على محمد 500" else "من الذي سدّد؟ اكتب الاسم، مثل: محمد سدد 2000")
                else -> ChatMessage(false, "كم المبلغ؟ اكتبها مثل: ${if (debt) "سجل على محمد رز بـ5000" else "محمد سدد 2000"}")
            }
        }
        val type = if (debt) "add_debt" else "add_payment"
        fun card(partyId: Long, name: String, isNew: Boolean): ChatMessage {
            val s = summaryOf(partyId, x)
            // 1.1.8 «سدد الي عليه كامل»: the amount is his whole current balance (in the account's currency)
            val amt: AmountHit = amount ?: run {
                if (s == null) return ChatMessage(false, "لم أجد «$name» في دفترك.")
                if (s.balance <= 0) return ChatMessage(false, "لا يوجد على ${s.party.name} شيء ✅ رصيده ${m(s.balance, s.party.cur(x.currency))}", about = s.party.id)
                // «دفع نصه»: half, rounded to a whole unit
                val minorDue = if (half) (s.balance / 2 / 100) * 100 else s.balance
                AmountHit(BigDecimal.valueOf(minorDue).movePointLeft(2), -1, -1, null)
            }
            val acc = s?.party?.cur(x.currency) ?: x.currency
            val given = curOf[amt.currency ?: Cur.YER].takeIf { amt.currency != null } ?: acc
            var minor = amt.minor; var rate = ""; var orig = 0L; var origCur = ""
            val lines = ArrayList<String>()
            lines += (if (debt) "على: " else "من: ") + name + if (isNew) " (زبون جديد)" else ""
            if (given != acc) {
                val r = x.rates.rate(given.code, acc.code)
                if (r == null) return ChatMessage(false, "لا يوجد لدي سعر صرف ${given.code} ← ${acc.code}. اضبطه من الإعدادات ‹أسعار الصرف›.")
                rate = Rates.plain(r); orig = amt.minor; origCur = given.code; minor = Rates.convert(orig, rate)
                lines += "المبلغ: ${Money.format(orig, given)} بسعر الشراء $rate"
                lines += "\u200F= \u2066${Money.number(minor)}\u2069 ${acc.labelAr}"
                lines += "سعر الشراء في ${x.rates.current.city} · آخر تحديث \u2066${x.rates.current.date}\u2069"
            } else lines += "المبلغ: ${Money.format(minor, acc)}"
            if (items.isNotBlank()) lines += "الأغراض: $items"
            if (method == "transfer") lines += "حوالة" + (if (transferCo.isNotBlank()) " · $transferCo" else "") + (if (transferNo.isNotBlank()) " · رقم \u2066$transferNo\u2069" else " (اكتب رقمها)")
            if (method == "wallet") {
                lines += "محفظة" + (if (transferCo.isNotBlank()) " · $transferCo" else "") + (if (transferNo.isNotBlank()) " · رقم العملية \u2066$transferNo\u2069" else " (اكتب رقم العملية)")
                if (senderPhone.isNotBlank()) lines += "جوال المرسل: \u2066$senderPhone\u2069"
            }
            if (date != x.today) lines += "التاريخ: ${Dates.short(date)}"
            if (full && !debt) lines += "سداد كامل الرصيد"
            if (half && !debt) lines += "سداد نصف الرصيد"
            if (s != null) lines += "الرصيد بعدها: ${Money.format(if (debt) s.balance + minor else s.balance - minor, acc)}"
            return ChatMessage(false, "تأكّد قبل التسجيل 👇", action = ChatAction(
                type = if (isNew) "add_party_$type" else type, party = name, name = name, partyId = partyId, minor = minor, items = items, day = date.toEpochDay(),
                lines = lines, origCurrency = origCur, origMinor = orig, rate = rate, method = method, transferNo = transferNo, note = transferCo, offline = true, senderPhone = senderPhone,
            ))
        }
        return when (who) {
            is NameMatch.Found -> card(who.c.id, who.c.name, false)
            is NameMatch.Ambiguous -> ChatMessage(false, "أيّهم تقصد؟ ($word ${amount?.let { Money.number(it.minor) } ?: if (half) "نصف الرصيد" else "كامل الرصيد"})", choices = who.options.map { o ->
                (card(o.id, o.name, false).action ?: ChatAction(type = type)).copy(party = o.name)
            })
            is NameMatch.NotFound -> when {
                who.guess.isBlank() -> ChatMessage(false, "على من؟ اكتب الاسم.")
                amount == null -> ChatMessage(false, "لم أجد «${who.guess}» في دفترك.", choices = who.suggestions.map { o -> (card(o.id, o.name, false).action ?: ChatAction(type = type)).copy(party = o.name) })
                // 1.1.8: a name not in the book is asked as a new customer — never split into a name + «items»
                who.suggestions.isEmpty() -> card(0, who.guess, true).let { m -> m.copy(text = "زبون جديد: ${who.guess}؟ تأكّد قبل التسجيل 👇") }
                else -> card(0, who.guess, true).let { m ->
                    m.copy(text = "زبون جديد: ${who.guess}؟ أم تقصد أحد هؤلاء؟ 👇",
                        choices = who.suggestions.map { o -> (card(o.id, o.name, false).action ?: ChatAction(type = type)).copy(party = o.name) })
                }
            }
            null -> ChatMessage(false, "على من؟ اكتب الاسم.")
        }
    }

    private fun createCustomer(c: Command.CreateCustomer, x: Ctx): ChatMessage {
        if (c.name.isBlank()) return ChatMessage(false, "ما اسم ${noun(x.role)}؟ مثال: أضف زبوناً جديداً اسمه ياسر 771234567")
        if (c.existing != null) return ChatMessage(false, "«${c.existing.name}» موجود لديك مسبقاً 👍")
        val lines = listOfNotNull("الاسم: ${c.name}", c.phone.takeIf { it.isNotBlank() }?.let { "الجوال: $it" })
        return ChatMessage(false, "هل أضيف ${if (x.role == Role.LANDLORD) "مستأجراً" else "زبوناً"} جديداً؟", action = ChatAction(type = "add_party", name = c.name, party = c.name, phone = c.phone, lines = lines, offline = true))
    }

    private val whenFmt = DateTimeFormatter.ofPattern("EEEE d MMMM · h:mm a", Locale.forLanguageTag("ar"))

    private fun reminder(c: Command.Reminder, x: Ctx): ChatMessage {
        val p = (c.who as? NameMatch.Found)?.c
        val atMillis = c.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = c.text.removePrefix("ذكرني").removePrefix("نبهني").trim().ifBlank { "تذكير" }
        val lines = listOfNotNull("الموعد: ${whenFmt.format(c.at)}", p?.let { "الحساب: ${it.name}" }, "النص: $text")
        return ChatMessage(false, "هل أضبط التذكير؟", action = ChatAction(type = "reminder", party = p?.name.orEmpty(), partyId = p?.id ?: 0, remindAt = atMillis, note = text, lines = lines, offline = true))
    }

    private fun undo(x: Ctx): ChatMessage {
        val r = x.lastLog ?: return ChatMessage(false, "لا توجد عمليات للتراجع عنها")
        return ChatMessage(false, "هل أتراجع عن آخر عملية؟", action = ChatAction(type = "undo", auditSeq = r.seq, lines = listOf(r.title, "وقتها: ${Dates.short(java.time.Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).toLocalDate())}"), offline = true))
    }

    // ---------------------------------------------------------------- an opened .daftari file
    fun backupSummary(b: BackupData, fileName: String, warnings: List<String>): String {
        val debts = b.entries.count { it.type == EntryType.CHARGE }
        val bal = b.parties.sumOf { p -> Ledger.summarize(p, b.entries.filter { it.partyId == p.id }, LocalDate.now()).balance.coerceAtLeast(0) }
        val cur = Currency.of(b.currency)
        val created = java.time.Instant.ofEpochMilli(b.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        return "فتحتُ النسخة «$fileName» ✅\n• ${b.parties.size} زبون\n• ${debts} دين و${b.entries.size - debts} سداد\n• إجمالي المتبقي فيها: ${Money.format(bal, cur)}\n• السجل: ${b.log.size} عملية" +
            (if (b.reminders.isNotEmpty()) "\n• ${b.reminders.size} تذكير" else "") + "\n• تاريخها: ${Dates.short(created)}" +
            (if (b.migratedFrom > 0) "\n(من إصدار قديم، تم تحويلها)" else "") + warnings.joinToString("") { "\n⚠️ $it" }
    }

    private fun backupAnswer(text: String, x: Ctx): ChatMessage? {
        val b = x.openedBackup ?: return null
        val w = ArabicText.words(text)
        if (w.none { it in setOf("النسخه", "النسخة", "الملف", "النسخه؟", "فيها", "فيه") }) return null
        val debts = b.entries.count { it.type == EntryType.CHARGE }
        return when {
            w.any { it in setOf("استوردها", "استرجعها", "ادمجها", "رجعها", "استورد", "استرجع") } ->
                ChatMessage(false, "هل أسترجع النسخة؟ لن تتكرر أي بيانات (دمج بالأرقام).", action = ChatAction(type = "import_backup", lines = listOf("${b.parties.size} زبون · ${b.entries.size} عملية · السجل ${b.log.size}"), offline = true))
            w.any { it.contains("زبون") || it.contains("زباين") || it.contains("زبائن") || it.contains("عملاء") || it.contains("حساب") } -> ChatMessage(false, "في النسخة ${b.parties.size} زبون.")
            w.any { it.contains("دين") || it.contains("ديون") } -> ChatMessage(false, "في النسخة $debts دين و${b.entries.size - debts} سداد.")
            w.any { it.contains("سداد") || it.contains("دفعات") } -> ChatMessage(false, "في النسخة ${b.entries.size - debts} سداد.")
            w.any { it.contains("سجل") || it.contains("لوق") } -> ChatMessage(false, "سجل النسخة يحتوي على ${b.log.size} عملية.")
            w.any { it.contains("تاريخ") || it.contains("متى") } -> ChatMessage(false, "النسخة مأخوذة ${Dates.short(java.time.Instant.ofEpochMilli(b.createdAt).atZone(ZoneId.systemDefault()).toLocalDate())}.")
            w.any { it.contains("اجمالي") || it.contains("كم") } -> ChatMessage(false, backupSummary(b, "النسخة", emptyList()))
            else -> null
        }
    }

    @Suppress("unused") private fun big(v: BigDecimal) = v
}
