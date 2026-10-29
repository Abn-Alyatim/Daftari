package com.daftari.app.ui.parties

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * The logo for a wallet or a transfer company (96 px webp in drawable-nodpi): wallets from their Google Play listings,
 * حاسب from the logo the owner sent (1.1.2), الكريمي from its Play app, النجم / الحزمي from their official accounts.
 * Anything typed under «أخرى» → null (a lettered badge where one is drawn).
 */
@DrawableRes fun walletIcon(name: String): Int? = when (PayMethods.wallet(name)?.key) {
    "onecash" -> R.drawable.wallet_onecash
    "jaib" -> R.drawable.wallet_jaib
    "jawali" -> R.drawable.wallet_jawali
    "floosak" -> R.drawable.wallet_floosak
    "hasib" -> R.drawable.wallet_hasib
    else -> companyIcon(name)
}

/** الكريمي / النجم / الحزمي (any spelling [PayMethods.company] knows), else null. */
@DrawableRes fun companyIcon(name: String): Int? = if (name.isBlank()) null else when (PayMethods.company(name)) {
    "الكريمي" -> R.drawable.transfer_kuraimi
    "النجم" -> R.drawable.transfer_najm
    "الحزمي" -> R.drawable.transfer_hazmi
    else -> null
}

private fun badgeColor(@Suppress("UNUSED_PARAMETER") name: String): Color = Color(0xFF3A3F4B)

/** The wallet's / transfer company's logo, or a rounded badge with its first letter. */
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


/** 1.1.3: «التقاط صورة» / «اختيار من المعرض» — black, grey border, blue icon, no grey fill (same family as the chips). */
@Composable
fun ReceiptSourceButton(label: String, @DrawableRes icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Df.c
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(44.dp).clip(shape).background(c.s1).border(1.dp, c.outline, shape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        com.daftari.app.ui.common.DfIcon(icon, 18.dp, if (c.isDark) c.primaryLight else c.primary)
        Spacer(Modifier.width(7.dp))
        T(label, 13.sp, FontWeight.Bold, c.text, maxLines = 1)
    }
}

/** 1.1.3: the attached receipt inside the payment sheet — thumbnail + «إزالة»; the two buttons below replace it. */
@Composable
fun ReceiptPreview(thumb: androidx.compose.ui.graphics.ImageBitmap?, onRemove: (() -> Unit)?) {
    val c = Df.c
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, c.outline, shape).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.outline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, contentDescription = "صورة الإيصال", Modifier.size(56.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else com.daftari.app.ui.common.DfIcon(R.drawable.ic_image, 22.dp, if (c.isDark) c.primaryLight else c.primary)
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            T("✓ أُرفقت صورة الإيصال", 12.5.sp, FontWeight.Bold, c.text, maxLines = 1)
            T("للاستبدال اختر صورة أخرى أدناه", 11.sp, FontWeight.Normal, c.muted, maxLines = 1)
        }
        if (onRemove != null) Row(
            Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, c.outline, RoundedCornerShape(10.dp)).clickable(onClick = onRemove).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.daftari.app.ui.common.DfIcon(R.drawable.ic_trash_2, 15.dp, c.bad)
            Spacer(Modifier.width(5.dp))
            T("إزالة", 12.sp, FontWeight.Bold, c.bad, maxLines = 1)
        }
    }
}

