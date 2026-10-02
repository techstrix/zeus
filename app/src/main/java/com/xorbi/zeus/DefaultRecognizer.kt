package com.xorbi.zeus

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Reads and writes the platform's choice of speech recognizer.
 *
 * The system resolves the recognizer for every `SpeechRecognizer.createSpeechRecognizer(context)`
 * call by reading `Settings.Secure.VOICE_RECOGNITION_SERVICE`
 * (frameworks/base: SpeechRecognitionManagerServiceImpl.getDefaultRecognitionServiceComponent),
 * and only lets a client use a service that is that default, the on-device service, or a
 * preinstalled app (checkPrivilege).
 *
 * Writing that setting needs WRITE_SECURE_SETTINGS, whose protection level is
 * `signature|privileged|development|role|installer`. The `development` flag is why
 * `adb shell pm grant` can hand it to a sideloaded app: no root, no ROM rebuild.
 *
 * Android TV ships no UI for this (packages/apps/Settings has the picker under
 * src/com/android/settings/language; packages/apps/TvSettings has no equivalent),
 * which is exactly why the switch lives in this app.
 */
object DefaultRecognizer {

    private const val TAG = "ZeusDefault"

    /**
     * `Settings.Secure.VOICE_RECOGNITION_SERVICE` is @hide, so it is absent from
     * the public SDK and has to be spelled out. Verified against frameworks/base
     * core/java/android/provider/Settings.java:
     *   public static final String VOICE_RECOGNITION_SERVICE = "voice_recognition_service";
     */
    const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, ZeusRecognitionService::class.java.name)

    /** The component the platform would use right now, or null if none is set. */
    fun current(context: Context): ComponentName? {
        val raw = Settings.Secure.getString(
            context.contentResolver, VOICE_RECOGNITION_SERVICE,
        ) ?: return null
        return ComponentName.unflattenFromString(raw)
    }

    fun isZeusDefault(context: Context): Boolean = current(context) == serviceComponent(context)

    fun hasPermission(context: Context): Boolean = context.checkSelfPermission(
        android.Manifest.permission.WRITE_SECURE_SETTINGS,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Points the platform at Zeus.
     *
     * @return true on success; false if the permission has not been granted.
     */
    fun makeZeusDefault(context: Context): Boolean {
        if (!hasPermission(context)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; cannot become the default")
            return false
        }
        val value = serviceComponent(context).flattenToShortString()
        val applied = Settings.Secure.putString(
            context.contentResolver, VOICE_RECOGNITION_SERVICE, value,
        )
        if (!applied) Log.w(TAG, "Settings.Secure.putString returned false")
        return applied && isZeusDefault(context)
    }

    /**
     * Restores whoever held the role before Zeus, or clears the setting so the
     * platform falls back to its own resolution order. Clearing is safer than
     * guessing Google's component name, which varies by build.
     */
    fun clearDefault(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return Settings.Secure.putString(
            context.contentResolver, VOICE_RECOGNITION_SERVICE, null,
        )
    }

    /** Human-readable label for whatever is currently the default. */
    fun describeCurrent(context: Context): String {
        val component = current(context) ?: return "none set (platform decides)"
        if (component == serviceComponent(context)) return "Zeus"
        return runCatching {
            val pm = context.packageManager
            val info = pm.getServiceInfo(component, 0)
            "${info.loadLabel(pm)} (${component.packageName})"
        }.getOrElse { component.flattenToShortString() }
    }

    /** The adb commands that grant the permission and perform the switch. */
    fun setupCommands(): List<String> = listOf(
        "adb shell pm grant com.xorbi.zeus android.permission.WRITE_SECURE_SETTINGS",
        "adb shell settings put secure voice_recognition_service com.xorbi.zeus/.ZeusRecognitionService",
    )
}