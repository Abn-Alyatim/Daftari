package com.daftari.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Currency
import com.daftari.app.core.Money
import com.daftari.app.domain.Status
import com.daftari.app.ui.theme.Df
import com.daftari.app.ui.theme.NotoArabic

@Composable
fun DfIcon(@DrawableRes id: Int, size: Dp = 20.dp, tint: Color = Df.c.text, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))

@Composable
fun DfCard(modifier: Modifier = Modifier, radius: Dp = 20.dp, border: Color = Df.c.outline, bg: Color = Df.c.s1, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
fun T(
    text: String, size: TextUnit = 14.sp, weight: FontWeight = FontWeight.Normal, color: Color = Df.c.text,
    modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, lineHeight: TextUnit = TextUnit.Unspecified,
) = Text(
    text, modifier = modifier, fontSize = size, fontWeight = weight, color = color, textAlign = align, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, fontFamily = NotoArabic, lineHeight = lineHeight,
)

/** Big amount: number then a small currency code (number on the right in RTL, like the mockups). */
@Composable
fun AmountText(minor: Long, currency: Currency, size: TextUnit = 22.sp, color: Color = Df.c.text, codeColor: Color = Df.c.muted, weight: FontWeight = FontWeight.ExtraBold) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Money.number(minor), fontSize = size, fontWeight = weight, color = color, fontFamily = NotoArabic,
            style = TextStyle(textDirection = TextDirection.Ltr))
        Spacer(Modifier.width(5.dp))
        Text(currency.code, fontSize = size * 0.42f, fontWeight = FontWeight.SemiBold, color = codeColor, fontFamily = NotoArabic,
            modifier = Modifier.padding(bottom = (size.value * 0.12f).dp))
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, @DrawableRes icon: Int? = null) {
    Row(
        Modifier.clip(CircleShape).background(bg).border(1.dp, fg.copy(alpha = 0.45f), CircleShape).padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { DfIcon(icon, 12.dp, fg); Spacer(Modifier.width(4.dp)) }
        T(text, 11.sp, FontWeight.Bold, fg)
    }
}

@Composable
fun StatusPill(status: Status) {
    val c = Df.c
    when (status) {
        Status.PAID -> Pill(status.labelAr, c.ok, c.okSoft)
        Status.PARTIAL -> Pill(status.labelAr, c.warn, c.warnSoft)
        Status.OWES -> Pill(status.labelAr, c.warn, c.warnSoft)
        Status.LATE -> Pill(status.labelAr, c.bad, c.badSoft)
    }
}

@Composable
fun statusColor(status: Status): Color = when (status) {
    Status.PAID -> Df.c.muted
    Status.PARTIAL, Status.OWES -> Df.c.warn
    Status.LATE -> Df.c.bad
}

@Composable
fun IconBox(@DrawableRes id: Int, onClick: (() -> Unit)? = null, size: Dp = 40.dp, tint: Color = Df.c.text, bg: Color = Df.c.s2) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier.size(size).clip(shape).background(bg).border(1.dp, Df.c.outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { DfIcon(id, 19.dp, tint) }
}

@Composable
fun Badge(text: String, size: Dp = 44.dp, fontSize: TextUnit = 15.sp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Df.c.primarySoft).border(1.dp, Df.c.outline2, RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center,
    ) { T(text, fontSize, FontWeight.ExtraBold, if (Df.c.isDark) Df.c.primaryLight else Df.c.primary, maxLines = 1) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true, bg: Color = Df.c.primary, fg: Color = Df.c.onPrimary, height: Dp = 52.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.height(height).clip(shape).background(if (enabled) bg else bg.copy(alpha = 0.4f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { DfIcon(icon, 19.dp, fg); Spacer(Modifier.width(8.dp)) }
        T(text, 15.sp, FontWeight.Bold, fg, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, iconTint: Color = Df.c.text, height: Dp = 52.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.height(height).clip(shape).background(Df.c.s2).border(1.dp, Df.c.outline, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { DfIcon(icon, 18.dp, iconTint); Spacer(Modifier.width(7.dp)) }
        T(text, 14.sp, FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Df.c
    Box(
        modifier.height(34.dp).clip(CircleShape).background(if (selected) c.primary else c.s1)
            .border(1.dp, if (selected) c.primary else c.outline, CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 12.5.sp, FontWeight.SemiBold, if (selected) c.onPrimary else c.muted, maxLines = 1) }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, 13.5.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) T(action, 12.sp, FontWeight.SemiBold, if (Df.c.isDark) Df.c.primaryLight else Df.c.muted, modifier = Modifier.clickable { onAction?.invoke() })
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, start: (@Composable () -> Unit)? = null, end: (@Composable RowScope.() -> Unit)? = null, centered: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (start != null) { start(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            if (subtitle != null && !centered) T(subtitle, 12.5.sp, FontWeight.Medium, Df.c.muted, maxLines = 1)
            T(title, if (centered) 15.sp else 20.sp, if (centered) FontWeight.Bold else FontWeight.ExtraBold, maxLines = 1)
        }
        if (end != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = end)
    }
}

/** Rounded input with label above, matching the mockups. */
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, ltr: Boolean = false, @DrawableRes icon: Int? = null,
) {
    val c = Df.c
    Column(modifier) {
        if (label.isNotEmpty()) { T(label, 11.5.sp, FontWeight.Medium, c.muted); Spacer(Modifier.height(6.dp)) }
        val shape = RoundedCornerShape(15.dp)
        Row(
            Modifier.fillMaxWidth().height(if (singleLine) 52.dp else 96.dp).clip(shape).background(c.s2).border(1.dp, c.outline, shape).padding(horizontal = 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (icon != null) { DfIcon(icon, 17.dp, c.muted); Spacer(Modifier.width(8.dp)) }
            Box(Modifier.weight(1f).padding(vertical = if (singleLine) 0.dp else 12.dp)) {
                if (value.isEmpty()) T(placeholder, 13.5.sp, color = c.muted2, maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = singleLine,
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, fontFamily = NotoArabic, fontWeight = FontWeight.SemiBold,
                        textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Df.c
    Box(
        Modifier.width(44.dp).height(26.dp).clip(CircleShape).background(if (on) c.primary else c.s3).clickable { onChange(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterStart else Alignment.CenterEnd,
    ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null, @DrawableRes actionIcon: Int = R.drawable.ic_plus) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Df.c.primarySoft), contentAlignment = Alignment.Center) {
            DfIcon(icon, 28.dp, if (Df.c.isDark) Df.c.primaryLight else Df.c.primary)
        }
        Spacer(Modifier.height(14.dp))
        T(title, 16.sp, FontWeight.Bold, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        T(body, 13.sp, color = Df.c.muted, align = TextAlign.Center, lineHeight = 21.sp)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(action, icon = actionIcon, onClick = onAction)
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Df.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s1).border(1.dp, c.outline, RoundedCornerShape(14.dp)).padding(4.dp),
    ) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (i == selected) c.s3 else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { T(o, 12.5.sp, FontWeight.Bold, if (i == selected) c.text else c.muted, maxLines = 1) }
        }
    }
}

@Suppress("unused")
val NoBorder = BorderStroke(0.dp, Color.Transparent)
