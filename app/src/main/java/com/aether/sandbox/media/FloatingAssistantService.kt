package com.aether.sandbox.media

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*
import com.aether.sandbox.MainActivity
import com.aether.sandbox.R

/** Foreground local video preview overlay. Does not supply frames to another app's camera. */
class FloatingAssistantService : Service() {
    private var window: LinearLayout? = null
    private var pipeline: SyntheticMediaPipeline? = null
    private lateinit var manager: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Video uji Aether", NotificationManager.IMPORTANCE_LOW))
            val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_aether)
                .setContentTitle("AetherSandbox: video uji aktif").setContentIntent(launch)
                .addAction(Notification.Action.Builder(null, "Tutup", stop).build()).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(10, notification)
            if (intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
            removeWindow()
            showWindow(uri)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun showWindow(uri: Uri) {
        manager = getSystemService(WindowManager::class.java)
        val width = (240 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 120 }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xee102033.toInt())
        }
        val title = TextView(this).apply { text = "Aether • geser panel"; setTextColor(-1); setPadding(8, 8, 8, 8) }
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        title.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; touchX = event.rawX; touchY = event.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + event.rawX - touchX).toInt().coerceAtLeast(0)
                    params.y = (initialY + event.rawY - touchY).toInt().coerceAtLeast(0)
                    manager.updateViewLayout(layout, params)
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
        val video = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    pipeline = SyntheticMediaPipeline(applicationContext, uri, Surface(surface)) { title.text = it }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { pipeline?.close(); pipeline = null; return true }
            }
        }
        layout.addView(title)
        layout.addView(video, LinearLayout.LayoutParams(-1, width * 3 / 4))
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "Pause"
            setOnClickListener { text = if (pipeline?.togglePlayback() == true) "Pause" else "Play" }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "Tutup"; setOnClickListener { stopSelf() } }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(controls)
        layout.addView(SeekBar(this).apply {
            max = 100; progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) { layout.alpha = (value / 100f).coerceAtLeast(0.3f) }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        })
        manager.addView(layout, params)
        window = layout
    }

    private fun removeWindow() {
        pipeline?.close(); pipeline = null
        window?.let { runCatching { manager.removeView(it) } }
        window = null
    }

    override fun onDestroy() { removeWindow(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }

    companion object {
        const val EXTRA_URI = "video_uri"
        private const val CHANNEL = "aether_video"
        private const val ACTION_STOP = "com.aether.sandbox.STOP_OVERLAY"
    }
}
