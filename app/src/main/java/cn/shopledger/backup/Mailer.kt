package cn.shopledger.backup

import cn.shopledger.data.Settings
import jakarta.activation.DataHandler
import jakarta.activation.FileDataSource
import jakarta.mail.*
import jakarta.mail.internet.*
import java.io.File
import java.util.Date
import java.util.Properties
import javax.net.ssl.SSLContext

class Mailer {
    fun validate(settings: Settings) {
        require(settings.smtpHost.isNotBlank() && settings.smtpHost.length <= 253 && !settings.smtpHost.any { it.isWhitespace() || it in "/:@" }) { "请输入有效SMTP服务器主机名" }
        require(settings.smtpPort in 1..65535) { "端口应在1至65535之间" }
        for (address in listOf(settings.sender, settings.recipient)) {
            require(address.isNotBlank() && address.length <= 254 && '\n' !in address && '\r' !in address) { "请输入有效邮箱地址" }
            InternetAddress(address, true).validate()
            require('@' in address) { "请输入完整邮箱地址" }
        }
    }
    fun send(settings: Settings, password: String, file: File?, id: String, subject: String? = null, text: String? = null, attachmentName: String? = null) {
        validate(settings)
        require(password.isNotBlank()) { "请先保存SMTP授权码" }
        val props = Properties().apply {
            setProperty("mail.transport.protocol", "smtps")
            setProperty("mail.smtps.host", settings.smtpHost)
            setProperty("mail.smtps.port", settings.smtpPort.toString())
            setProperty("mail.smtps.auth", "true")
            setProperty("mail.smtps.ssl.enable", "true")
            setProperty("mail.smtps.ssl.checkserveridentity", "true")
            val protocols = SSLContext.getDefault().supportedSSLParameters.protocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }.joinToString(" ")
            require(protocols.isNotEmpty()) { "此设备不支持安全的TLS协议" }
            setProperty("mail.smtps.ssl.protocols", protocols)
            setProperty("mail.smtps.connectiontimeout", "30000")
            setProperty("mail.smtps.timeout", "60000")
            setProperty("mail.smtps.writetimeout", "60000")
        }
        val session = Session.getInstance(props)
        val message = object : MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID", "<$id@shopledger.local>") }
        }.apply {
            setFrom(InternetAddress(settings.sender))
            setRecipient(Message.RecipientType.TO, InternetAddress(settings.recipient))
            setSubject(subject ?: if (file == null) "小店账本 · 邮箱连接测试" else "小店账本 · 完整加密备份", "UTF-8")
            sentDate = Date()
            val body = MimeBodyPart().apply {
                setText(text ?: if (file == null) "收到这封测试邮件，说明小店账本的邮箱已连接成功。" else
                    "附件是截至生成时刻的完整账本。请保存附件及独立备份密码。密码遗失将无法恢复。备份编号：$id", "UTF-8")
            }
            setContent(MimeMultipart().apply {
                addBodyPart(body)
                if (file != null) addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(file))
                    fileName = attachmentName ?: "ledger-$id.ledgerbak"
                    disposition = Part.ATTACHMENT
                })
            })
        }
        session.getTransport("smtps").use { transport ->
            transport.connect(settings.smtpHost, settings.smtpPort, settings.sender, password)
            transport.sendMessage(message, message.allRecipients)
        }
    }
}
