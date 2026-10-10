// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: a small client for the CSQTT server web panel API.

package com.csqtt.client

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

internal data class PanelServer(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
)

internal data class PanelDns(
    val provider: String,
    val primary: String,
    val secondary: String,
)

internal class PanelException(message: String) : IOException(message)

/**
 * Talks to the server's own web panel (HTTPS with a self-signed certificate).
 *
 * The certificate is pinned on first use: the first fingerprint seen for a
 * host:port is remembered and later connections must present the same one.
 */
internal class PanelApi(context: Context, private val server: PanelServer) {
    private val pins = context.applicationContext
        .getSharedPreferences("mayak_panel_pins", Context.MODE_PRIVATE)
    private val pinKey = "${server.host}:${server.port}"
    private var cookie: String? = null

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not used")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(leaf.encoded)
                .joinToString("") { byte -> "%02x".format(byte) }
            val known = pins.getString(pinKey, null)
            if (known == null) {
                pins.edit().putString(pinKey, fingerprint).apply()
            } else if (known != fingerprint) {
                throw CertificateException(CERTIFICATE_CHANGED)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Forgets the remembered certificate, for example after the server was reinstalled. */
    fun forgetCertificate() {
        pins.edit().remove(pinKey).apply()
    }

    private fun encodeCredential(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val shifted = ByteArray(bytes.size) { index -> ((bytes[index].toInt() + 47) and 0xff).toByte() }
        return "c1:" + Base64.encodeToString(shifted, Base64.NO_WRAP)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val host = if (server.host.contains(':') && !server.host.startsWith("[")) {
            "[${server.host}]"
        } else {
            server.host
        }
        val connection = try {
            URL("https://$host:${server.port}$path").openConnection() as HttpsURLConnection
        } catch (error: Exception) {
            throw PanelException("Неверный адрес сервера")
        }
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            connection.sslSocketFactory = sslContext.socketFactory
            // The panel is addressed by IP and uses a self-signed certificate;
            // identity is checked by the pinned fingerprint above.
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("content-type", "application/json")
            cookie?.let { connection.setRequestProperty("Cookie", it) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.headerFields.entries
                .filter { entry -> entry.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { entry -> entry.value.orEmpty() }
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.startsWith("csqtt_session=") && it.length > "csqtt_session=".length }
                ?.let { cookie = it }
            return code to text
        } catch (error: SSLException) {
            if (generateSequence<Throwable>(error) { it.cause }.any { it.message == CERTIFICATE_CHANGED }) {
                throw PanelException(CERTIFICATE_CHANGED)
            }
            throw PanelException("Не удалось установить защищённое соединение с панелью")
        } catch (error: PanelException) {
            throw error
        } catch (error: IOException) {
            throw PanelException(
                "Сервер не отвечает напрямую. В мобильной сети он может быть закрыт — попробуйте через Wi-Fi."
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun login() {
        val (code, _) = request(
            "POST",
            "/api/login",
            JSONObject()
                .put("user", encodeCredential(server.user))
                .put("pass", encodeCredential(server.password)),
        )
        when (code) {
            200 -> if (cookie == null) throw PanelException("Панель не выдала сессию")
            401 -> throw PanelException("Неверный логин или пароль панели. Проверьте окно «Авторизация».")
            429 -> throw PanelException("Слишком много попыток входа. Подождите несколько минут.")
            else -> throw PanelException("Панель ответила кодом $code")
        }
    }

    private fun logout() {
        runCatching { request("POST", "/api/logout") }
        cookie = null
    }

    private suspend fun <T> session(block: () -> T): T = withContext(Dispatchers.IO) {
        if (server.host.isBlank()) throw PanelException("Сначала укажите IP сервера во вкладке «Деплой»")
        if (server.user.isBlank() || server.password.isBlank()) {
            throw PanelException("Укажите логин и пароль панели в окне «Авторизация» во вкладке «Деплой»")
        }
        login()
        try {
            block()
        } finally {
            logout()
        }
    }

    private fun readDns(): PanelDns {
        val (code, text) = request("GET", "/api/settings")
        if (code != 200) throw PanelException("Не удалось прочитать настройки сервера (код $code)")
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw PanelException("Сервер прислал непонятный ответ")
        return PanelDns(
            provider = json.optString("dns_provider", "custom"),
            primary = json.optString("dns_primary", ""),
            secondary = json.optString("dns_secondary", ""),
        )
    }

    suspend fun getDns(): PanelDns = session { readDns() }

    /** Applies DNS on the server; the server pushes it to connected clients without a restart. */
    suspend fun setDns(providerId: String?, primary: String, secondary: String): PanelDns = session {
        val body = JSONObject()
        if (providerId != null) {
            body.put("dns_provider", providerId)
        } else {
            body.put("dns_primary", primary)
            body.put("dns_secondary", secondary)
        }
        val (code, text) = request("POST", "/api/settings", body)
        if (code != 200) {
            throw PanelException(
                if (text.isNotBlank() && text.length < 120) "Сервер отклонил DNS: $text" else "Сервер отклонил DNS (код $code)"
            )
        }
        readDns()
    }

    companion object {
        const val CERTIFICATE_CHANGED = "Сертификат панели изменился"
    }
}
