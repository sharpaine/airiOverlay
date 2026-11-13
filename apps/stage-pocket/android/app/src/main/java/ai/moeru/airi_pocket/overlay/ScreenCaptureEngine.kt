package ai.moeru.airi_pocket.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Keeps one MediaProjection virtual display alive and hands out JPEG snapshots on demand.
 *
 * NOTICE: Android 14+ forbids creating a second VirtualDisplay from one projection token, so the display is created
 * once and the newest frame is held. A snapshot encodes the held frame instead of starting a new capture.
 */
class ScreenCaptureEngine(
    private val context: Context,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("airi-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    // Touched only on `handler`'s thread.
    private var heldImage: Image? = null
    private var heldArrivalMs = 0L

    fun start(resultCode: Int, data: Intent) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
           val projection = manager.getMediaProjection(resultCode, data)
       ?: throw IllegalStateException("MediaProjection unavailable")
        this.projection = projection

        // NOTICE: Android 14+ requires a registered callback before createVirtualDisplay.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                onStopped()
            }
        }, handler)

        val metrics = realMetrics()
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ source ->
            val next = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            heldImage?.close()
            heldImage = next
            heldArrivalMs = SystemClock.uptimeMillis()
        }, handler)
        this.reader = reader

        display = projection.createVirtualDisplay(
            "airi-overlay-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    /**
     * Encodes the first frame that arrived at or after [notBeforeMs], so the floating bubble is not in it.
     * Gives up waiting after [timeoutMs] and encodes whatever is held. Calls [callback] on the capture thread.
     */
    fun snapshot(notBeforeMs: Long, timeoutMs: Long = 600, callback: (ByteArray?) -> Unit) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        fun attempt() {
            val image = heldImage
            val fresh = heldArrivalMs >= notBeforeMs
            if (image != null && (fresh || SystemClock.uptimeMillis() >= deadline)) {
                callback(encode(image))
            }
            else if (SystemClock.uptimeMillis() >= deadline) {
                callback(null)
            }
            else {
                handler.postDelayed(::attempt, 25)
            }
        }
        handler.post(::attempt)
    }

    fun release() {
        handler.post {
            heldImage?.close()
            heldImage = null
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
    }

    fun dispose() {
        release()
        thread.quitSafely()
    }

    private fun encode(image: Image): ByteArray? = try {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }
    catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        }
        else {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }
}
