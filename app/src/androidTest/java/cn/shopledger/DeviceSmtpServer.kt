package cn.shopledger

import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Test-only loopback TLS SMTP endpoint. No external mailbox or OS trust store is used. */
class DeviceSmtpServer(context: SSLContext) : Closeable {
    private val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    val port: Int get() = server.localPort
    private val executor = Executors.newSingleThreadExecutor()
    val received = executor.submit<String> {
        server.soTimeout=90_000
        (server.accept() as SSLSocket).use { socket ->
            socket.soTimeout=30_000
            val input=socket.inputStream.bufferedReader(Charsets.US_ASCII)
            val output=socket.outputStream.bufferedWriter(Charsets.US_ASCII)
            fun reply(text: String) { output.write(text+"\r\n"); output.flush() }
            reply("220 localhost device test SMTP")
            var data=false
            var auth=0
            val body=StringBuilder()
            while (true) {
                val line=input.readLine() ?: break
                if (data) { if (line==".") { data=false; reply("250 accepted") } else body.appendLine(line); continue }
                if (auth>0) {
                    if (auth==1) { auth=2; reply("334 UGFzc3dvcmQ6") } else { auth=0; reply("235 authenticated") }
                    continue
                }
                when {
                    line.startsWith("EHLO") -> reply("250-localhost\r\n250-AUTH LOGIN\r\n250 OK")
                    line.startsWith("AUTH LOGIN") -> { auth=1; reply("334 VXNlcm5hbWU6") }
                    line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") -> reply("250 OK")
                    line=="DATA" -> { data=true; reply("354 End with dot") }
                    line=="QUIT" -> { reply("221 goodbye"); break }
                    else -> reply("250 OK")
                }
            }
            body.toString()
        }
    }
    override fun close() { server.close(); executor.shutdownNow(); executor.awaitTermination(2,TimeUnit.SECONDS) }
    companion object {
        fun tlsContext(): SSLContext {
            val store=KeyStore.getInstance("PKCS12").apply {
                InstrumentationRegistry.getInstrumentation().context.assets.open("smtp-test.p12").use { load(it,"test-store-password".toCharArray()) }
            }
            val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"test-store-password".toCharArray()) }
            val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(keys.keyManagers,trust.trustManagers,null) }
        }
    }
}
