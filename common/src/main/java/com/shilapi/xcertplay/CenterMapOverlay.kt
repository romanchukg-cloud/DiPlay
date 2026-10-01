package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Lab: the dashboard map (CarPlay stream 111) as a floating card on the centre screen while DiPlay
 * is in the background, for example over BYD's home or map home. The stream has one surface, so
 * while the card is up the dashboard does not show the map. Needs "display over other apps"
 * (SYSTEM_ALERT_WINDOW). A tap opens CarPlay; dragging moves the card.
 */
internal object CenterMapOverlay {
    const val TAG = "DiPlay-CenterMap"
    private const val SHOW_DELAY_MILLIS = 600L
    private const val RELEASE_DELAY_MILLIS = 1_000L
    private const val WIDTH_FRACTION = 0.36

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null

    /** The CarPlay screen, asked to show the card once no DiPlay screen is in front. */
    var requestShow: (() -> Unit)? = null
    private val showIfBackground = Runnable { if (!diPlayInFront()) requestShow?.invoke() }

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** A DiPlay screen left the front; the card follows if nothing of DiPlay replaces it. */
    fun onDiPlayScreenHidden() {
        main.removeCallbacks(showIfBackground)
        main.postDelayed(showIfBackground, SHOW_DELAY_MILLIS)
    }

    /** A DiPlay screen is in front: the card goes. */
    fun onDiPlayScreenShown() {
        main.removeCallbacks(showIfBackground)
        hide()
    }

    val shown: Boolean get() = root != null

    /**
     * Adds the card. [onSurface] gets its surface, and null when the card goes, before the surface is
     * released. Main thread.
     */
    fun show(
        context: Context,
        aspect: Double,
        onSurface: (Surface?) -> Unit,
        onTap: () -> Unit,
    ): Boolean {
        if (root != null) return true
        if (!permitted(context)) return false
        val windows = context.getSystemService(WindowManager::class.java) ?: return false
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val height = (width / aspect).toInt()
        val radius = 24f * metrics.density / 2
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // the TextureView needs it
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, metrics.widthPixels - width - (32 * metrics.density).toInt())
                .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (96 * metrics.density).toInt())
                .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
            title = "DiPlay centre map"
        }
        var surface: Surface? = null
        val video = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(texture).also(onSurface)
                    Log.i(TAG, "card surface ${w}x$h")
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    // Hand the stream back first; the decoder switches on its own thread, so the
                    // old surface stays valid a little longer.
                    onSurface(null)
                    val old = surface
                    surface = null
                    main.postDelayed({ old?.release(); texture.release() }, RELEASE_DELAY_MILLIS)
                    return false
                }
            }
        }
        val card = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            addView(video, FrameLayout.LayoutParams(-1, -1))
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = (startX + dx).toInt().coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    else onTap()
                }
            }
            true
        }
        return try {
            windows.addView(card, params)
            root = card
            Log.i(TAG, "card shown ${width}x$height at ${params.x},${params.y}")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "card failed", error)
            false
        }
    }

    /** Removes the card; its surface goes back through onSurface(null). Main thread. */
    fun hide() {
        val view = root ?: return
        root = null
        runCatching { view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        Log.i(TAG, "card hidden")
    }

    // A DiPlay activity in front makes the process foreground; the session service alone does not.
    private fun diPlayInFront(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private const val PREFS = "diplay_center_map_lab"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
}
