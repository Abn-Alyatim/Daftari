package com.daftari.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.CityRates
import com.daftari.app.core.Rates
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import java.time.LocalDate

/** One currency row of the table: name | شراء | بيع (both editable; only شراء is ever used in a calculation). */
@Composable
private fun RateRow(name: String, code: String, buy: String, onBuy: (String) -> Unit, sell: String, onSell: (String) -> Unit) {
    val c = Df.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(0.9f)) { T(name, 13.sp, FontWeight.Bold, maxLines = 1); T(code, 11.sp, color = c.muted, maxLines = 1) }
        Field("", buy, onBuy, Modifier.weight(1f), keyboard = KeyboardType.Decimal, ltr = true)
        Field("", sell, onSell, Modifier.weight(1f), keyboard = KeyboardType.Decimal, ltr = true)
    }
}

/** أسعار الصرف: صنعاء / عدن, شراء | بيع, manual with the date; «تحديث من النت» fills both. Conversions use شراء only (1.1.2). */
@Composable
fun RatesScreen(rates: Rates, status: String?, onSave: (Rates) -> Unit, onFetch: () -> Unit, onBack: () -> Unit) {
    val c = Df.c
    var city by remember(rates) { mutableStateOf(rates.city) }
    val cur = rates.cities.firstOrNull { it.city == city } ?: rates.current
    var sb by remember(cur) { mutableStateOf(cur.sarBuy) }; var ss by remember(cur) { mutableStateOf(cur.sarSell) }
    var ub by remember(cur) { mutableStateOf(cur.usdBuy) }; var us by remember(cur) { mutableStateOf(cur.usdSell) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("أسعار الصرف", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { rates.cities.forEach { cr -> Chip(cr.city, city == cr.city, Modifier.weight(1f)) { city = cr.city } } }
            T("آخر تحديث: \u2066${cur.date}\u2069", 12.sp, color = c.muted, modifier = Modifier.padding(top = 10.dp))
            DfCard(Modifier.padding(top = 10.dp).fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    T("كم ريالاً يمنياً مقابل الوحدة", 12.sp, color = c.muted)
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        T("العملة", 12.5.sp, FontWeight.Bold, c.muted, modifier = Modifier.weight(0.9f))
                        T("شراء", 13.sp, FontWeight.ExtraBold, if (c.isDark) c.primaryLight else c.primary, modifier = Modifier.weight(1f))
                        T("بيع (للعلم)", 13.sp, FontWeight.ExtraBold, c.muted, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    RateRow("ريال سعودي", "SAR", sb, { sb = it }, ss, { ss = it })
                    Spacer(Modifier.height(10.dp))
                    RateRow("دولار أمريكي", "USD", ub, { ub = it }, us, { us = it })
                }
            }
            T("تُحسب كل العمليات بسعر «الشراء» فقط، وسعر «البيع» للعلم ولا يدخل في أي حساب. يُحفظ السعر الذي حُسبت به كل عملية معها، ولا يتغير إذا عدّلت الجدول.", 11.5.sp, color = c.muted2, modifier = Modifier.padding(top = 10.dp))
            status?.let { T(it, 12.sp, FontWeight.SemiBold, if (it.contains("✓")) c.ok else c.warn, modifier = Modifier.padding(top = 10.dp)) }
            PrimaryButton("حفظ", Modifier.padding(top = 14.dp).fillMaxWidth(), R.drawable.ic_check) {
                onSave(rates.copy(city = city).with(CityRates(city, sb.trim(), ss.trim(), ub.trim(), us.trim(), LocalDate.now().toString())))
            }
            GhostButton("تحديث من الإنترنت", Modifier.padding(top = 10.dp).fillMaxWidth(), R.drawable.ic_download, onClick = onFetch)
            Spacer(Modifier.height(24.dp))
        }
    }
}
