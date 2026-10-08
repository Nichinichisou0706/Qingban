package io.github.ming.qingban

import android.content.Context
import io.github.ming.qingban.domain.ModelProfile
import io.github.ming.qingban.platform.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class ModelProfilesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("model_configuration", Context.MODE_PRIVATE)
    private val cipher = object : ModelSecretCipher {
        override fun encrypt(value: String) = EncryptedModelSecret(value.reversed(), "synthetic-iv")
        override fun decrypt(secret: EncryptedModelSecret) = secret.data.reversed()
    }
    private fun store() = SecureModelSettings(context, cipher)
    private fun profile(id: String) = ModelProfile(id, "Profile $id", baseUrl = "https://example.com/v1", model = "chat-$id")
    @Before fun clear() { prefs.edit().clear().commit() }
    @Test fun legacyCiphertextMigratesOnceWithoutLosingTheSelectedModel() {
        prefs.edit().putString("base_url", "https://api.deepseek.com/v1/").putString("model", "old-chat")
            .putString("secret", "terces-dlo").putString("iv", "synthetic-iv").putBoolean("json_mode", false).commit()
        val settings = store(); val migrated = settings.profiles().single()
        assertEquals("deepseek", migrated.providerId); assertFalse(migrated.jsonMode)
        assertEquals("old-secret", settings.apiKey()); assertEquals("old-chat", settings.summary()["model"])
        assertEquals("terces-dlo", prefs.getString("secret:legacy-model", null)); assertFalse(prefs.contains("secret"))
        assertEquals(migrated, store().profiles().single())
    }
    @Test fun multipleProfilesKeepKeysIndependentAcrossRestartAndSwitch() {
        val settings = store()
        settings.saveProfile(profile("a"), "key-a", true); settings.saveProfile(profile("b"), "key-b", false)
        assertEquals("a", settings.activeId()); assertEquals("key-a", settings.apiKey())
        store().activate("b")
        val (active, key) = store().activeConnection()!!
        assertEquals("b", active.id); assertEquals("key-b", key); assertEquals("chat-b", store().summary()["model"])
        assertEquals(2, store().profiles().size)
        assertFalse(prefs.all.values.any { it.toString().contains("key-a") || it.toString().contains("key-b") })
    }
    @Test fun editWithBlankKeyPreservesOnlyTheSameDestination() {
        val settings = store(); settings.saveProfile(profile("a"), "key-a", true)
        settings.saveProfile(profile("a").copy(model = "new-chat"), "", true)
        assertEquals("key-a", settings.apiKey()); assertEquals("new-chat", settings.summary()["model"])
        assertTrue(runCatching { settings.saveProfile(profile("a").copy(baseUrl = "https://other.example/v1"), "", true) }.isFailure)
        assertEquals("https://example.com/v1", settings.activeConnection()!!.first.baseUrl)
    }
    @Test fun deletingActiveProfileReturnsToOfflineAndKeepsTheOtherKey() {
        val settings = store(); settings.saveProfile(profile("a"), "key-a", true); settings.saveProfile(profile("b"), "key-b", false)
        settings.delete("a")
        assertNull(settings.activeId()); assertEquals("false", settings.summary()["has_key"])
        assertFalse(prefs.contains("secret:a")); assertEquals("key-b", settings.apiKey("b"))
        settings.activate("b"); assertEquals("chat-b", settings.summary()["model"])
    }
    @Test fun disablingAndEditingDoNotDeleteSavedProfiles() {
        val settings = store(); settings.saveProfile(profile("a"), "key-a", true)
        settings.activate(null); assertNull(settings.activeConnection()); assertEquals(1, settings.profiles().size)
        settings.activate("a"); settings.saveProfile(profile("a"), "", false)
        assertNull(settings.activeId()); assertEquals("key-a", settings.apiKey("a"))
    }
    @Test fun invalidProfileDoesNotChangeExistingConfiguration() {
        val settings = store(); settings.saveProfile(profile("a"), "key-a", true)
        assertTrue(runCatching { settings.saveProfile(profile("b").copy(baseUrl = "http://remote.example/v1"), "key-b", true) }.isFailure)
        assertTrue(runCatching { settings.activate("unknown") }.isFailure)
        assertEquals("a", settings.activeId()); assertEquals(1, settings.profiles().size)
    }
}
