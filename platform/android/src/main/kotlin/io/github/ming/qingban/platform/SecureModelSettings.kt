package io.github.ming.qingban.platform

import android.content.Context
import android.security.keystore.*
import android.util.Base64
import io.github.ming.qingban.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedModelSecret(val data: String, val iv: String)
interface ModelSecretCipher {
    fun encrypt(value: String): EncryptedModelSecret
    fun decrypt(secret: EncryptedModelSecret): String
}
class AndroidModelSecretCipher : ModelSecretCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("qingban_model_key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("qingban_model_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(value: String): EncryptedModelSecret {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return EncryptedModelSecret(Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
    }
    override fun decrypt(secret: EncryptedModelSecret): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(secret.iv, Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(secret.data, Base64.NO_WRAP)))
    }
}

/** Stores profile metadata separately from per-profile encrypted credentials. */
class SecureModelSettings(context: Context, private val cipher: ModelSecretCipher = AndroidModelSecretCipher()) {
    private val prefs = context.getSharedPreferences("model_configuration", Context.MODE_PRIVATE)

    @Synchronized fun profiles(): List<ModelProfile> {
        migrateLegacy()
        val rows = JSONArray(prefs.getString("profiles", "[]"))
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index); val id = row.getString("id")
            val models = row.optJSONArray("models") ?: JSONArray()
            ModelProfile(id, row.getString("name"), row.optString("provider", "custom"), row.getString("base"), row.getString("model"),
                row.optBoolean("json", true), prefs.contains("secret:$id"), (0 until models.length()).map { models.getString(it) })
        }
    }
    @Synchronized fun activeId(): String? { migrateLegacy(); return prefs.getString("active", null) }

    @Synchronized fun saveProfile(profile: ModelProfile, apiKey: String, makeActive: Boolean) {
        require(profile.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "配置标识不正确" }
        require(profile.name.trim().length in 1..80) { "请填写配置名称（最多 80 字）" }
        require(profile.model.trim().length in 1..256 && profile.model.none(Char::isISOControl)) { "请填写有效模型名称" }
        val base = ModelConnectionAddress.validate(profile.baseUrl)
        val current = profiles(); val previous = current.firstOrNull { it.id == profile.id }
        require(apiKey.isNotBlank() || previous?.hasKey == true) { "请填写 API 密钥" }
        require(apiKey.isNotBlank() || base == previous?.baseUrl) { "接口已变化，请重新填写该接口的密钥" }
        val secret = apiKey.trim().takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        val saved = profile.copy(name = profile.name.trim(), baseUrl = base, model = profile.model.trim(), modelIds = profile.modelIds.distinct().take(10_000))
        val updated = if (previous == null) current + saved else current.map { if (it.id == saved.id) saved else it }
        val editor = prefs.edit().putString("profiles", encode(updated))
        secret?.let { editor.putString("secret:${saved.id}", it.data).putString("iv:${saved.id}", it.iv) }
        if (makeActive) editor.putString("active", saved.id)
        else if (activeId() == saved.id) editor.remove("active")
        editor.apply()
    }
    @Synchronized fun activate(id: String?) {
        require(id == null || profiles().any { it.id == id && it.hasKey } && apiKey(id) != null) { "配置或密钥不可用，请重新保存" }
        prefs.edit().apply { if (id == null) remove("active") else putString("active", id) }.apply()
    }
    @Synchronized fun delete(id: String) {
        val remaining = profiles().filterNot { it.id == id }
        val editor = prefs.edit().putString("profiles", encode(remaining)).remove("secret:$id").remove("iv:$id")
        if (activeId() == id) editor.remove("active")
        editor.apply()
    }
    @Synchronized fun summary(): Map<String, String> {
        val active = activeId(); val profile = profiles().firstOrNull { it.id == active }
        return mapOf("base_url" to profile?.baseUrl.orEmpty(), "model" to profile?.model.orEmpty(),
            "has_key" to (profile?.hasKey == true).toString(), "json_mode" to (profile?.jsonMode != false).toString())
    }
    @Synchronized fun apiKey(id: String? = activeId()): String? {
        if (id == null) return null
        return runCatching {
            val data = prefs.getString("secret:$id", null) ?: return null
            val iv = prefs.getString("iv:$id", null) ?: return null
            cipher.decrypt(EncryptedModelSecret(data, iv))
        }.getOrNull()
    }
    @Synchronized fun activeConnection(): Pair<ModelProfile, String>? {
        val id = activeId() ?: return null
        val profile = profiles().firstOrNull { it.id == id } ?: return null
        val secret = apiKey(id) ?: return null
        return profile to secret
    }
    @Synchronized fun save(baseUrl: String, model: String, apiKey: String, jsonMode: Boolean) {
        val id = activeId() ?: UUID.randomUUID().toString()
        val previous = profiles().firstOrNull { it.id == id }
        saveProfile(ModelProfile(id, previous?.name ?: "我的模型", ModelProviders.infer(baseUrl), baseUrl, model, jsonMode), apiKey, true)
    }
    @Synchronized fun clear() { prefs.edit().clear().apply() }

    private fun encode(profiles: List<ModelProfile>) = JSONArray().apply {
        profiles.forEach { add -> put(JSONObject().put("id", add.id).put("name", add.name).put("provider", add.providerId)
            .put("base", add.baseUrl).put("model", add.model).put("json", add.jsonMode).put("models", JSONArray(add.modelIds))) }
    }.toString()

    private fun migrateLegacy() {
        if (prefs.contains("profiles")) return
        val base = prefs.getString("base_url", "").orEmpty().trim().trimEnd('/'); val model = prefs.getString("model", "").orEmpty()
        val editor = prefs.edit()
        if (base.isNotBlank() || model.isNotBlank() || prefs.contains("secret")) {
            val id = "legacy-model"
            editor.putString("profiles", encode(listOf(ModelProfile(id, "原有模型", ModelProviders.infer(base), base, model, prefs.getBoolean("json_mode", true)))))
                .putString("active", id)
            prefs.getString("secret", null)?.let { editor.putString("secret:$id", it) }
            prefs.getString("iv", null)?.let { editor.putString("iv:$id", it) }
        } else editor.putString("profiles", "[]")
        editor.remove("base_url").remove("model").remove("json_mode").remove("secret").remove("iv")
        // Migration is atomic and reuses ciphertext from the previous version.
        check(editor.commit()) { "无法迁移模型配置，请稍后重试" }
    }
}
