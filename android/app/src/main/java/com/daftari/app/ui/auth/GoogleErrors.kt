package com.daftari.app.ui.auth

/**
 * Why a Google sign-in / Drive authorization failed, from the exception's class name and message (Credential Manager) or the
 * Play-services status code (AuthorizationClient). Pure Kotlin so it is unit-tested; the texts are فصحى and name the cause.
 */
object GoogleErrors {
    enum class Kind { CANCELLED, DEVELOPER, NO_ACCOUNT, NETWORK, PLAY_SERVICES, INTERRUPTED, UNKNOWN }

    private val devRx = Regex("(^|[^0-9])10:|developer console|developer_error|\\[28444]")

    /** Credential Manager: [type] is the exception's simple class name. */
    fun fromCredential(type: String, message: String?): Kind {
        val m = message.orEmpty().lowercase()
        return when {
            type == "GetCredentialCancellationException" -> Kind.CANCELLED
            devRx.containsMatchIn(m) -> Kind.DEVELOPER
            type == "GetCredentialInterruptedException" -> Kind.INTERRUPTED
            type == "GetCredentialProviderConfigurationException" || type == "GetCredentialUnsupportedException" -> Kind.PLAY_SERVICES
            type == "NoCredentialException" -> Kind.NO_ACCOUNT
            Regex("(^|[^0-9])7:").containsMatchIn(m) || "network" in m || "unable to resolve host" in m -> Kind.NETWORK
            Regex("(^|[^0-9])(16|12501):").containsMatchIn(m) || "cancel" in m -> Kind.CANCELLED
            else -> Kind.UNKNOWN
        }
    }

    /** Play services ApiException status codes (CommonStatusCodes / GoogleSignInStatusCodes). */
    fun fromStatus(code: Int?): Kind = when (code) {
        10 -> Kind.DEVELOPER
        7 -> Kind.NETWORK
        16, 12501 -> Kind.CANCELLED
        17, 9 -> Kind.PLAY_SERVICES
        4 -> Kind.NO_ACCOUNT
        14, 15 -> Kind.INTERRUPTED
        else -> Kind.UNKNOWN
    }

    /** [what] = «تسجيل الدخول بـ Google» or «ربط Google Drive»; [detail] = a code/class shown for UNKNOWN. Null for CANCELLED (nothing to show). */
    fun text(kind: Kind, what: String = "تسجيل الدخول بـ Google", detail: String? = null): String? = when (kind) {
        Kind.CANCELLED -> null
        Kind.DEVELOPER -> "تعذّر $what: إعداد مشروع Google Cloud لا يطابق هذا التطبيق (خطأ 10). تأكّد أن عميل OAuth من نوع Android يحمل اسم الحزمة \u2066com.daftari.app\u2069 وبصمة SHA-1 الصحيحة، وأن معرّف عميل الويب من المشروع نفسه."
        Kind.NO_ACCOUNT -> "تعذّر $what: لم يُعثر على حساب Google صالح. أضِف حساب Google من إعدادات الجوال ← الحسابات ثم أعِد المحاولة. وإن كان الحساب موجوداً فالسبب غالباً أن بصمة SHA-1 أو اسم الحزمة في عميل Android لا يطابقان التطبيق."
        Kind.NETWORK -> "تعذّر $what: لا يوجد اتصال بالإنترنت 📶"
        Kind.PLAY_SERVICES -> "تعذّر $what: خدمات Google Play غير متوفرة على هذا الجوال أو تحتاج إلى تحديث."
        Kind.INTERRUPTED -> "تعذّر $what: انقطعت العملية قبل اكتمالها، أعِد المحاولة."
        Kind.UNKNOWN -> "تعذّر $what" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ". إن كان التطبيق في وضع الاختبار فتأكّد أن بريدك مضاف إلى «Test users» في Google Cloud."
    }
}
