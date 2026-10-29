package com.daftari.app.export

import com.daftari.app.audit.Action
import com.daftari.app.audit.AuditRecord
import com.daftari.app.audit.Verify
import com.daftari.app.core.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** «كشف السجل» (PDF / Excel / Word / image) from the filtered log rows. */
object LogReport {
    private val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

    fun build(rows: List<AuditRecord>, from: LocalDate?, to: LocalDate?, party: String, actions: Set<String>, verify: Verify?,
              business: String, owner: String, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Report {
        val title = buildString {
            append("كشف السجل")
            if (party.isNotBlank()) append(" — $party")
        }
        val r = ReportBuilder.custom(title, listOf("#", "الوقت", "العملية", "التفاصيل", "بواسطة", "عن طريق"),
            rows.sortedBy { it.seq }.map { listOf(it.seq.toString(), fmt.format(Instant.ofEpochMilli(it.at).atZone(zone)), Action.labelAr(it.action), it.title, it.actor, it.via) },
            business, owner, today)
        val period = if (from != null || to != null) "${from?.let { Dates.numeric(it) } ?: "البداية"} ← ${to?.let { Dates.numeric(it) } ?: Dates.numeric(today)}" else "كل الفترات"
        return r.copy(
            fileStem = "daftari_log_$today", subtitle = period, weights = listOf(0.45f, 1.35f, 0.75f, 3.4f, 1.0f, 0.8f), landscape = true,
            summary = listOfNotNull(
                "عدد العمليات" to rows.size.toString(),
                "النوع" to (if (actions.isEmpty()) "الكل" else actions.joinToString("، ") { Action.labelAr(it) }),
                verify?.let { "سلامة السجل" to (if (it.ok) "سليم ✓ مختوم SHA-256" else "⚠️ ${it.reason}") },
            ),
        )
    }
}
