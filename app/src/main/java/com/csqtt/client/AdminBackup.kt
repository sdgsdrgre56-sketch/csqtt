// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: all settings (servers, panel access, notes, countries, own DNS, sync tokens) saved to one
// file locked with a password, and put back on this or another phone.

package com.csqtt.client

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal class BackupException(message: String) : Exception(message)

internal object AdminBackup {
    private const val FORMAT = "mayak-admin-backup"
    private const val ITERATIONS = 120_000

    /** Small files kept outside the main settings that are worth keeping. Caches are left out. */
    private val EXTRA_PREFS = listOf(
        "mayak_admin_sync",          // GitHub and Yandex Disk tokens for sending lists to people
        "mayak_admin_dns",           // «Мои DNS»
        "mayak_server_flags_manual", // countries set by hand
        "mayak_panel_pins",          // trusted panel certificates
    )

    // ---- values <-> JSON (secrets decrypted, so the file works on a new phone) ------------------

    private fun encodeValue(secure: SecureStringStore, value: Any?): JSONObject? = when (value) {
        is String -> {
            val plain = if (value.startsWith(CsqttConstants.SecureStore.VERSION_PREFIX)) secure.decrypt(value) else null
            if (plain != null) JSONObject().put("t", "secret").put("v", plain) else JSONObject().put("t", "s").put("v", value)
        }
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Double -> JSONObject().put("t", "d").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun decodeValue(secure: SecureStringStore, o: JSONObject): Any? = when (o.optString("t")) {
        "secret" -> secure.encrypt(o.optString("v"))
        "s" -> o.optString("v")
        "i" -> o.optInt("v")
        "l" -> o.optLong("v")
        "b" -> o.optBoolean("v")
        "f" -> o.optDouble("v").toFloat()
        "d" -> o.optDouble("v")
        "ss" -> o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet<String>()
        else -> null
    }

    private fun encodeMap(secure: SecureStringStore, map: Map<String, *>): JSONObject {
        val out = JSONObject()
        for ((key, value) in map) encodeValue(secure, value)?.let { out.put(key, it) }
        return out
    }

    private fun decodeMap(secure: SecureStringStore, o: JSONObject?): Map<String, Any> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, Any>()
        for (key in o.keys()) {
            val value = o.optJSONObject(key)?.let { decodeValue(secure, it) } ?: continue
            out[key] = value
        }
        return out
    }

    // ---- password lock (PBKDF2 + AES-GCM) -------------------------------------------------------

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun lock(plain: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString()
    }

    private fun unlock(text: String, password: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw BackupException("Это не файл резервной копии Маяк Админ")
        return try {
            val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.doFinal(data).toString(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw BackupException("Неверный пароль или файл повреждён")
        }
    }

    // ---- save / restore ---------------------------------------------------------------------------

    /** Writes every setting into [uri], locked with [password]. Returns how many servers were in it. */
    suspend fun save(context: Context, store: SettingsStore, uri: Uri, password: String): Int = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val secure = SecureStringStore(appContext)
        val extras = JSONObject()
        for (name in EXTRA_PREFS) {
            extras.put(name, encodeMap(secure, appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all))
        }
        val body = JSONObject()
            .put("created", System.currentTimeMillis() / 1000L)
            .put("app", BuildConfig.VERSION_NAME)
            .put("settings", encodeMap(secure, store.allPreferencesForBackup()))
            .put("extras", extras)
        val servers = store.adminPanelProfiles(MayakConfig.ADMIN_SERVERS).count { it.isConfigured }
        val stream = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupException("Не удалось записать файл")
        stream.use { it.write(lock(body.toString(), password).toByteArray(Charsets.UTF_8)) }
        servers
    }

    /** Replaces every setting with the ones from [uri]. Returns how many servers came back. */
    suspend fun restore(context: Context, store: SettingsStore, uri: Uri, password: String): Int = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val secure = SecureStringStore(appContext)
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw BackupException("Не удалось прочитать файл")
        if (text.length > 5_000_000) throw BackupException("Файл слишком большой для резервной копии")
        val body = runCatching { JSONObject(unlock(text, password)) }.getOrElse { failure ->
            if (failure is BackupException) throw failure
            throw BackupException("Файл повреждён")
        }
        val settings = decodeMap(secure, body.optJSONObject("settings"))
        if (settings.isEmpty()) throw BackupException("В файле нет настроек")
        store.replaceAllPreferencesFromBackup(settings)
        val extras = body.optJSONObject("extras")
        for (name in EXTRA_PREFS) {
            val values = decodeMap(secure, extras?.optJSONObject(name))
            val edit = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Double -> edit.putFloat(key, value.toFloat())
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            edit.commit()
        }
        ServerFlags.manualVersion.value += 1
        store.adminPanelProfiles(MayakConfig.ADMIN_SERVERS).count { it.isConfigured }
    }
}
