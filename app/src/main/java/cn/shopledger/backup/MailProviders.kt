package cn.shopledger.backup

object MailProviders {
    data class Provider(val name: String, val host: String)
    fun find(email: String): Provider? = when (email.trim().substringAfterLast('@', "").lowercase()) {
        "qq.com", "foxmail.com" -> Provider("QQ邮箱", "smtp.qq.com")
        "163.com" -> Provider("163邮箱", "smtp.163.com")
        "126.com" -> Provider("126邮箱", "smtp.126.com")
        "yeah.net" -> Provider("网易邮箱", "smtp.yeah.net")
        "gmail.com", "googlemail.com" -> Provider("Gmail", "smtp.gmail.com")
        else -> null
    }
}
