package com.daftari.app.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.daftari.app.core.Phone
import java.io.File

/** Call / WhatsApp / SMS / share sheet. Every path fails soft with an Arabic toast. */
object Share {
    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    fun uriFor(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)

    fun call(ctx: Context, phone: String) {
        if (phone.isBlank()) return toast(ctx, "لا يوجد رقم مسجّل")
        start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))))
    }

    fun sms(ctx: Context, phone: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", body)
        start(ctx, i)
    }

    /** Text to a number on WhatsApp (wa.me works with WhatsApp and WhatsApp Business). */
    fun whatsappText(ctx: Context, phone: String, body: String) {
        val norm = Phone.normalize(phone)
        if (norm == null) return toast(ctx, "رقم الجوال غير صحيح")
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${Phone.waDigits(norm)}?text=" + Uri.encode(body))))
    }

    /** A file (PDF/PNG) to a number on WhatsApp; falls back to the share sheet if WhatsApp is missing. */
    fun whatsappFile(ctx: Context, phone: String, file: File, mime: String, caption: String) {
        val uri = uriFor(ctx, file)
        val norm = Phone.normalize(phone)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val i = Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg)
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (norm != null) i.putExtra("jid", Phone.waDigits(norm) + "@s.whatsapp.net")
            try { ctx.startActivity(i); return } catch (_: ActivityNotFoundException) { }
        }
        file(ctx, file, mime, caption)
    }

    fun file(ctx: Context, file: File, mime: String, text: String = "") {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, file))
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, Intent.createChooser(i, "مشاركة").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun open(ctx: Context, file: File, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, file), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, i)
    }

    private fun start(ctx: Context, i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { toast(ctx, "لا يوجد تطبيق يفتح هذا") }
    }

    /** Sends a file straight to Google Drive's «save» screen when Drive is installed, else the share sheet. */
    fun toDrive(ctx: Context, file: File) {
        val i = Intent(Intent.ACTION_SEND).setType("application/json").setPackage("com.google.android.apps.docs")
            .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, file)).putExtra(Intent.EXTRA_TITLE, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { file(ctx, file, "application/json", "نسخة دفتري") }
    }
}
