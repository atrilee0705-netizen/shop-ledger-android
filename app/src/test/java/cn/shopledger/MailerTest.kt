package cn.shopledger

import cn.shopledger.backup.Mailer
import cn.shopledger.data.Settings
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class MailerTest {
    private fun tls(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply {
            MailerTest::class.java.getResourceAsStream("/smtp-test.p12").use { load(requireNotNull(it),"test-store-password".toCharArray()) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"test-store-password".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        // Robolectric registers Conscrypt globally; use the host JDK TLS provider for this JVM server.
        return SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers,trust.trustManagers,null) }
    }
    @Test fun smtpOverTlsActuallyTransfersTheAttachment() {
        withServer { port, transcript ->
            val file = File.createTempFile("smtp-attachment-", ".ledgerbak").apply { writeText("encrypted-ledger-test-payload") }
            try {
                Mailer().send(config(port),"test-authorization-code",file,"stable-test-id")
                val received = transcript.get(10,TimeUnit.SECONDS)
                assertTrue(received.contains("Message-ID: <stable-test-id@shopledger.local>"))
                assertTrue(received.contains("ledger-stable-test-id.ledgerbak"))
                val message = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()), received.byteInputStream(Charsets.US_ASCII))
                val multipart = message.content as jakarta.mail.Multipart
                assertArrayEquals(file.readBytes(), multipart.getBodyPart(1).inputStream.use { it.readBytes() })
                assertFalse(received.contains("test-authorization-code"))
            } finally { file.delete() }
        }
    }
    @Test fun badCertificateHostnameIsRejected() {
        withServer { port, _ ->
            assertThrows(MessagingException::class.java) { Mailer().send(config(port).copy(smtpHost="127.0.0.1"),"test-authorization-code",null,"test") }
        }
    }
    @Test fun authenticationFailureIsNotReportedAsSuccess() {
        withServer(rejectAuth = true) { port, _ ->
            assertThrows(AuthenticationFailedException::class.java) { Mailer().send(config(port),"wrong-code",null,"test") }
        }
    }
    private fun config(port: Int) = Settings(smtpHost="localhost",smtpPort=port,sender="sender@example.com",recipient="recipient@example.com")
    private fun withServer(rejectAuth: Boolean = false, test: (Int,java.util.concurrent.Future<String>) -> Unit) {
        val original = SSLContext.getDefault()
        val ssl = tls()
        SSLContext.setDefault(ssl)
        val server = ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.soTimeout = 10_000
        val executor = Executors.newSingleThreadExecutor()
        val transcript = executor.submit<String> {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                val input = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                val output = socket.outputStream.bufferedWriter(Charsets.US_ASCII)
                fun reply(text: String) { output.write(text+"\r\n"); output.flush() }
                reply("220 localhost Test SMTP")
                var data = false
                var auth = 0
                val body = StringBuilder()
                while (true) {
                    val line = input.readLine() ?: break
                    if (data) { if (line == ".") { data = false; reply("250 accepted") } else body.appendLine(line); continue }
                    if (auth > 0) {
                        if (auth == 1) { auth=2; reply("334 UGFzc3dvcmQ6") }
                        else { auth=0; reply(if (rejectAuth) "535 Authentication failed" else "235 authenticated") }
                        continue
                    }
                    when {
                        line.startsWith("EHLO") -> reply("250-localhost\r\n250-AUTH LOGIN\r\n250 OK")
                        line.startsWith("AUTH LOGIN") -> { auth=1; reply("334 VXNlcm5hbWU6") }
                        line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") -> reply("250 OK")
                        line == "DATA" -> { data=true; reply("354 End with dot") }
                        line == "QUIT" -> { reply("221 goodbye"); break }
                        else -> reply("250 OK")
                    }
                }
                body.toString()
            }
        }
        try { test(server.localPort,transcript) }
        catch (error: Throwable) {
            try { transcript.get(2,TimeUnit.SECONDS) } catch (serverError: Exception) { error.addSuppressed(serverError) }
            throw error
        }
        finally { server.close(); executor.shutdownNow(); SSLContext.setDefault(original) }
    }
}
