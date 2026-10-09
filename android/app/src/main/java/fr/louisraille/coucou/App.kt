// The app's own corner of the phone: preferences, the secret that guards the
// link, the owner check before a command is allowed, and haptics.

package fr.louisraille.coucou

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Link.load(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

// ── Preferences ───────────────────────────────────────────────────────────────

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("coucou", Context.MODE_PRIVATE)
    }

    /** For preferences a single screen owns (as @AppStorage on the iPhone). */
    fun bool(key: String, default: Boolean = false) = sp.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun int(key: String, default: Int = 0) = sp.getInt(key, default)
    fun setInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    fun string(key: String, default: String = ""): String = sp.getString(key, default) ?: default
    fun setString(key: String, value: String) = sp.edit().putString(key, value).apply()

    // PhoneSettings on the iPhone.

    /** A notification when an agent finishes or fails (on by default). */
    var notifyDone: Boolean
        get() = bool("notifyDone", true)
        set(v) = setBool("notifyDone", v)

    /** Mochi's own sounds instead of the phone's default one (on by default). */
    var mochiSounds: Boolean
        get() = bool("mochiSounds", true)
        set(v) = setBool("mochiSounds", v)

    var quietHours: Boolean
        get() = bool("quietHours")
        set(v) = setBool("quietHours", v)

    /** Minutes after midnight. */
    var quietFrom: Int
        get() = int("quietFrom", 22 * 60)
        set(v) = setInt("quietFrom", v)

    var quietTo: Int
        get() = int("quietTo", 8 * 60)
        set(v) = setInt("quietTo", v)

    /** Inside the quiet hours: only what waits on you (approvals, questions) makes a sound. */
    fun isQuiet(): Boolean {
        if (!quietHours) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val from = quietFrom
        val to = quietTo
        if (from == to) return false
        return if (from < to) now in from until to else now >= from || now < to
    }

    var onboardingDone: Boolean
        get() = bool("onboardingDone")
        set(v) = setBool("onboardingDone", v)

    /** The port the link listens on. */
    var port: Int
        get() = int("port", 47821)
        set(v) = setInt("port", v)

    // ── Secrets: wrapped by a key that never leaves the Android Keystore ──────

    private const val KEY_ALIAS = "coucou-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    /** Null when it can no longer be read (restored backup, new device). */
    private fun open(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var token: String? = null

    /** The link's token, made on first use: whoever has it may send sessions here. */
    fun linkToken(): String {
        token?.let { return it }
        synchronized(this) {
            token?.let { return it }
            val fresh = sp.getString("linkToken", null)?.let { open(it) } ?: run {
                val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also {
                    sp.edit().putString("linkToken", seal(it)).apply()
                }
            }
            token = fresh
            return fresh
        }
    }

    /** A new token: every computer linked with the old one has to be linked again. */
    fun resetLinkToken() {
        synchronized(this) {
            sp.edit().remove("linkToken").apply()
            token = null
        }
    }
}

// ── The owner check ───────────────────────────────────────────────────────────

/** Fingerprint, face or the screen lock before allowing a command. Never skipped. */
object OwnerCheck {
    suspend fun confirm(reason: String): Boolean {
        val context = App.instance
        // A phone anyone can open cannot vouch for its owner: nothing is allowed from it.
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            Toast.makeText(context, "Set a screen lock on this phone to allow commands from it.", Toast.LENGTH_LONG).show()
            return false
        }
        return suspendCancellableCoroutine { done ->
            val builder = BiometricPrompt.Builder(context).setTitle("Coucou").setSubtitle(reason)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            val signal = CancellationSignal()
            done.invokeOnCancellation { signal.cancel() }
            builder.build().authenticate(
                signal, context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (done.isActive) done.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (done.isActive) done.resume(false)
                    }
                },
            )
        }
    }
}

// ── Haptics ───────────────────────────────────────────────────────────────────

/** Small taps under the finger for the actions that reach the computer. */
object Haptics {
    private fun play(effect: Int) {
        val vibrator = App.instance.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }

    fun success() = play(VibrationEffect.EFFECT_DOUBLE_CLICK)
    fun warning() = play(VibrationEffect.EFFECT_HEAVY_CLICK)
    fun error() = play(VibrationEffect.EFFECT_HEAVY_CLICK)
    fun impact() = play(VibrationEffect.EFFECT_CLICK)
}
