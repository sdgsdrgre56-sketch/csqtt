package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Веб-панель сервера Бухты: создаёт клиента и возвращает ключ buhta://… */
object BuhtaPanel {
    fun autoKey(host: String, port: Int, panelUser: String, panelPass: String, clientName: String, onLine: (String) -> Unit): String? {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        fun call(method: String, path: String, body: String?, cookie: String?): Triple<Int, String, String?> {
            val c = URL("https://$host:$port$path").openConnection() as HttpsURLConnection
            c.sslSocketFactory = ctx.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.requestMethod = method
            c.setRequestProperty("X-Buhta", "1")
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            val setCookie = c.headerFields["Set-Cookie"]?.firstOrNull()?.substringBefore(";")
            return Triple(code, text, setCookie)
        }
        var cookie: String? = null
        var lastErr = ""
        for (i in 1..8) {
            try {
                val r = call("POST", "/api/login", JSONObject().put("login", panelUser).put("password", panelPass).toString(), null)
                if (r.first == 200 && r.third != null) { cookie = r.third; break }
                lastErr = "вход в панель: код ${r.first}"
            } catch (e: Exception) {
                lastErr = e.message.orEmpty().take(120)
            }
            Thread.sleep(2000)
        }
        if (cookie == null) { onLine("[ERR] Панель недоступна: $lastErr. Проверь, что порт $port открыт у хостера"); return null }
        return try {
            val list = JSONArray(call("GET", "/api/clients", null, cookie).second)
            var id = ""
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.optString("name") == clientName) { id = o.optString("id"); break }
            }
            if (id.isEmpty()) {
                val r = call("POST", "/api/clients", JSONObject().put("name", clientName).put("limit_mbps", 0).toString(), cookie)
                id = JSONObject(r.second).getString("id")
            }
            JSONObject(call("GET", "/api/clients/$id/link", null, cookie).second).getString("link")
        } catch (e: Exception) {
            onLine("[ERR] Не удалось создать ключ: ${e.message}")
            null
        }
    }

}
