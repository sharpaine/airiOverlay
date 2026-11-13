package ai.moeru.airi_pocket.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin

/** Lets the web layer turn the floating AIRI bubble on and off and ask for the permissions it needs. */
@CapacitorPlugin(name = "AiriOverlay")
class AiriOverlayPlugin : Plugin() {
    @PluginMethod
    fun getStatus(call: PluginCall) {
        call.resolve(statusObject())
    }

    @PluginMethod
    fun requestOverlayPermission(call: PluginCall) {
        if (Settings.canDrawOverlays(context)) {
            call.resolve(statusObject())
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        startActivityForResult(call, intent, "overlayPermissionResult")
    }

    @ActivityCallback
    fun overlayPermissionResult(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        call?.resolve(statusObject())
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (!Settings.canDrawOverlays(context)) {
            call.reject("overlay-permission-missing")
            return
        }
        if (AiriOverlayService.running) {
            call.resolve(statusObject())
            return
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(call, manager.createScreenCaptureIntent(), "projectionResult")
    }

    @ActivityCallback
    fun projectionResult(call: PluginCall?, result: ActivityResult) {
        if (call == null)
            return
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            call.reject("capture-denied")
            return
        }
        val intent = Intent(context, AiriOverlayService::class.java)
            .setAction(AiriOverlayService.ACTION_START)
            .putExtra(AiriOverlayService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(AiriOverlayService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, intent)
        // The service sets `running` a moment later, so report the intent here instead of polling.
        call.resolve(statusObject().apply { put("running", true) })
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        context.startService(
            Intent(context, AiriOverlayService::class.java).setAction(AiriOverlayService.ACTION_STOP),
        )
        call.resolve(statusObject().apply { put("running", false) })
    }

    private fun statusObject() = JSObject().apply {
        put("overlayGranted", Settings.canDrawOverlays(context))
        put("running", AiriOverlayService.running)
    }
}
