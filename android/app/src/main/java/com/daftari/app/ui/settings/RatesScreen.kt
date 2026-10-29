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

/** أسعار الصرف: صنعاء / عدن, buy & sell, manual with the date; «تحديث من النت» is optional. */
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
                    T("الريال السعودي — كم ريال يمني", 13.sp, FontWeight.Bold)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("شراء", sb, { sb = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, ltr = true); Field("بيع", ss, { ss = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, ltr = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    T("الدولار — كم ريال يمني", 13.sp, FontWeight.Bold)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("شراء", ub, { ub = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, ltr = true); Field("بيع", us, { us = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, ltr = true)
                    }
                }
            }
            T("السداد بعملة أخرى يُحسب بسعر «الشراء»، والدين بعملة أخرى بسعر «البيع». يُحفظ السعر الذي حُسبت به كل عملية معها، ولا يتغير إذا عدّلت الجدول.", 11.5.sp, color = c.muted2, modifier = Modifier.padding(top = 10.dp))
            status?.let { T(it, 12.sp, FontWeight.SemiBold, if (it.contains("✓")) c.ok else c.warn, modifier = Modifier.padding(top = 10.dp)) }
            PrimaryButton("حفظ", Modifier.padding(top = 14.dp).fillMaxWidth(), R.drawable.ic_check) {
                onSave(rates.copy(city = city).with(CityRates(city, sb.trim(), ss.trim(), ub.trim(), us.trim(), LocalDate.now().toString())))
            }
            GhostButton("تحديث من الإنترنت", Modifier.padding(top = 10.dp).fillMaxWidth(), R.drawable.ic_download, onClick = onFetch)
            Spacer(Modifier.height(24.dp))
        }
    }
}
