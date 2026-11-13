package ai.moeru.airi_pocket.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import ai.moeru.airi_pocket.R

/** The small draggable circle. Tap opens AIRI, drag moves it, and it snaps to the nearest screen edge. */
class FloatingBubble(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val sizePx = (56 * density).toInt()
    private val marginPx = (6 * density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.resources.displayMetrics.widthPixels - sizePx - marginPx
        y = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
    }

    private val view = ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher_round)
        scaleType = ImageView.ScaleType.CENTER_CROP
        elevation = 8 * density
        alpha = 0.92f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
        contentDescription = "AIRI"
    }

    private var attached = false

    init {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop)
                        dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        update()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging)
                        snapToEdge()
                    else if (event.actionMasked == MotionEvent.ACTION_UP)
                        onTap()
                }
            }
            true
        }
    }

    fun show() {
        if (!attached) {
            windowManager.addView(view, params)
            attached = true
        }
        view.visibility = View.VISIBLE
    }

    /** Hides without removing the window, so the next screen capture does not contain the bubble. */
    fun hide() {
        view.visibility = View.INVISIBLE
    }

    fun remove() {
        if (attached) {
            runCatching { windowManager.removeView(view) }
            attached = false
        }
    }

    private fun update() {
        if (attached)
            runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun snapToEdge() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val targetX = if (params.x + sizePx / 2 < screenWidth / 2) marginPx else screenWidth - sizePx - marginPx
        params.y = params.y.coerceIn(marginPx * 4, screenHeight - sizePx - marginPx * 4)
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                update()
            }
            start()
        }
    }
}
