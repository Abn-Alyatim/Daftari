package com.daftari.app.ui.parties

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.PayMethods
import com.daftari.app.ui.common.T
import com.daftari.app.ui.theme.Df

/** Each wallet's launcher icon (from its Google Play listing, 96 px webp); حاسب has no app of its own → a lettered badge. */
@DrawableRes fun walletIcon(name: String): Int? = when (PayMethods.wallet(name)?.key) {
    "onecash" -> R.drawable.wallet_onecash
    "jaib" -> R.drawable.wallet_jaib
    "jawali" -> R.drawable.wallet_jawali
    "floosak" -> R.drawable.wallet_floosak
    else -> null
}

private fun badgeColor(name: String): Color = when (PayMethods.wallet(name)?.key) {
    "hasib" -> Color(0xFF0B7A3E)
    else -> Color(0xFF3A3F4B)
}

/** The wallet's icon, or a rounded badge with its first letter. */
@Composable
fun WalletLogo(name: String, size: Dp = 22.dp) {
    val icon = walletIcon(name)
    val shape = RoundedCornerShape(size * 0.28f)
    if (icon != null) Image(painterResource(icon), name, Modifier.size(size).clip(shape))
    else Box(Modifier.size(size).clip(shape).background(badgeColor(name)), contentAlignment = Alignment.Center) {
        T(name.trim().removePrefix("ال").take(1).ifBlank { "؟" }, (size.value * 0.5f).sp, FontWeight.ExtraBold, Color.White, maxLines = 1)
    }
}

/** A chip with the wallet's icon: blue border + blue text when chosen, grey border otherwise (no grey fill). */
@Composable
fun WalletChip(label: String, logo: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Df.c
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(if (selected) c.primarySoft else c.s1)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.primary else c.outline, CircleShape).clickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) WalletLogo(logo, 28.dp) else Spacer(Modifier.width(8.dp))
        Spacer(Modifier.width(8.dp))
        T(label, 13.sp, FontWeight.Bold, if (selected) (if (c.isDark) c.primaryLight else c.primary) else c.text, maxLines = 1)
    }
}
