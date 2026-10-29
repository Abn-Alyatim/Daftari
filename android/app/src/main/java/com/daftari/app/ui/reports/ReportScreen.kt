package com.daftari.app.ui.reports

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.export.ExportFormat
import com.daftari.app.export.Report
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df

enum class Range(val labelAr: String) { MONTH("هذا الشهر"), LAST_MONTH("الشهر الماضي"), YEAR("هذه السنة"), ALL("الكل") }

/**
 * Export preview: the real rendered page (same renderer as the PNG/PDF), period chips, format chips, share and WhatsApp/SMS.
 * [preview] is a bitmap of the report; null while it is being drawn.
 */
@Composable
fun ReportScreen(
    title: String, report: Report?, preview: Bitmap?, range: Range, format: ExportFormat, canSendToParty: Boolean,
    onBack: () -> Unit, onRange: (Range) -> Unit, onFormat: (ExportFormat) -> Unit, onShare: () -> Unit, onOpen: () -> Unit,
    onWhatsApp: () -> Unit, onSms: () -> Unit,
) {
    val c = Df.c
    Column(Modifier.fillMaxSize()) {
        TopBar(title, centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) }, end = { IconBox(R.drawable.ic_download, onOpen) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.entries.forEach { r -> Chip(r.labelAr, r == range) { onRange(r) } }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportFormat.entries.forEach { f ->
                val on = f == format
                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(13.dp)).background(if (on) c.primarySoft else c.s1)
                        .border(1.dp, if (on) c.primary else c.outline, RoundedCornerShape(13.dp)).clickable { onFormat(f) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    DfIcon(when (f) { ExportFormat.PDF -> R.drawable.ic_file_text; ExportFormat.DOCX -> R.drawable.ic_file_type; ExportFormat.XLSX -> R.drawable.ic_file_spreadsheet; ExportFormat.PNG -> R.drawable.ic_image }, 16.dp,
                        when (f) { ExportFormat.PDF -> Color(0xFFFF6464); ExportFormat.DOCX -> Color(0xFF4C8DFF); ExportFormat.XLSX -> Color(0xFF21A366); ExportFormat.PNG -> Color(0xFFB98CFF) })
                    Spacer(Modifier.width(6.dp))
                    T(f.labelAr, 12.5.sp, FontWeight.Bold, if (on) c.text else c.muted)
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(c.s2).border(1.dp, c.outline, RoundedCornerShape(20.dp)).padding(12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (preview != null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Image(preview.asImageBitmap(), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White), contentScale = ContentScale.FillWidth)
                }
            } else T("جارٍ تجهيز المعاينة…", 13.sp, color = c.muted, modifier = Modifier.padding(40.dp))
        }
        if (report != null) T("${report.rows.size} سطر · ${report.subtitle}", 11.5.sp, color = c.muted, modifier = Modifier.padding(horizontal = 18.dp), maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canSendToParty) {
                PrimaryButton("واتساب", Modifier.weight(1.3f), R.drawable.ic_si_whatsapp, bg = Color(0xFF1FAF5A), fg = Color.White, onClick = onWhatsApp)
                GhostButton("SMS", Modifier.weight(1f), R.drawable.ic_message_square, onClick = onSms)
                GhostButton("", Modifier.width(56.dp), R.drawable.ic_share_2, onClick = onShare)
            } else {
                PrimaryButton("مشاركة", Modifier.weight(1f), R.drawable.ic_share_2, onClick = onShare)
            }
        }
    }
}
