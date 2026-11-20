package com.example.audioplayercompose

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class TopOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val CHANNEL_ID = "top_island_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_OVERLAY = "com.example.audioplayercompose.STOP_OVERLAY"
        private const val AUDIO_URL =
            "https://irsv.upmusics.com/singletracks/2026/11/Ai%20-%20Kash%20Zoodtar%20Baroon%20Miomad%20UpMusics.mp3"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        setupTopEdgeOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY keeps the top overlay alive even if the app is completely closed
        return START_STICKY
    }

    private fun setupTopEdgeOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Flush to the very top physical edge of the screen (y = 0, overlaps status bar / cutout)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TopOverlayService)
            setViewTreeViewModelStoreOwner(this@TopOverlayService)
            setViewTreeSavedStateRegistryOwner(this@TopOverlayService)
            setContent {
                TopNotchAudioIsland(audioUrl = AUDIO_URL)
            }
        }

        overlayView = composeView
        windowManager.addView(composeView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "نوار بالای صفحه",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش دائمی پلیر در حاشیه بالای گوشی"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TopOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("پلیر نوار بالا فعال است")
            .setContentText("برای بستن کامل نوار بالای صفحه دکمه زیر را بزنید")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "بستن نوار",
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@OptIn(UnstableApi::class)
@Composable
fun TopNotchAudioIsland(audioUrl: String) {
    val context = LocalContext.current

    // Figma Rectangle 1 specs: width=200dp, height=40dp, fill=#000000, cornerRadius=(0, 0, 8, 8)
    val islandShape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = 8.dp,
        bottomEnd = 8.dp
    )

    val activeGreen = Color(0xFF44B678)
    val inactiveGray = Color(0xFFF0F0F0)
    val barCount = 10

    // Alternating heights scaled cleanly to fit inside the 40dp top black island
    val baseHeights = remember {
        listOf(24f, 12f, 24f, 12f, 24f, 12f, 24f, 12f, 24f, 12f)
    }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0.4f) }
    var audioSessionId by remember { mutableIntStateOf(0) }

    val barAmplitudes = remember {
        mutableStateListOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    val hasAudioPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(audioUrl))
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                    exoPlayer.pause()
                    progress = 0f
                }
            }
        }
        exoPlayer.addListener(listener)
        audioSessionId = exoPlayer.audioSessionId

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    DisposableEffect(audioSessionId, hasAudioPermission, isPlaying) {
        var visualizer: Visualizer? = null
        if (hasAudioPermission && audioSessionId != 0 && isPlaying) {
            try {
                visualizer = Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                vis: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) {
                            }

                            override fun onFftDataCapture(
                                vis: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (fft != null && fft.size >= 22) {
                                    for (i in 0 until barCount) {
                                        val binIndex = (i + 1) * 2
                                        val rfk = fft[binIndex].toFloat()
                                        val ifk = fft[binIndex + 1].toFloat()
                                        val magnitude = hypot(rfk, ifk)
                                        val normalized = min(1f, max(0f, magnitude / 45f))
                                        barAmplitudes[i] =
                                            barAmplitudes[i] * 0.35f + normalized * 0.65f
                                    }
                                }
                            }
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        false,
                        true
                    )
                    enabled = true
                }
            } catch (_: Exception) {
                visualizer = null
            }
        }

        onDispose {
            try {
                visualizer?.enabled = false
                visualizer?.release()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val duration = exoPlayer.duration
            val current = exoPlayer.currentPosition
            if (duration > 0) {
                progress = (current.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            }
            if (!hasAudioPermission || audioSessionId == 0) {
                for (i in 0 until barCount) {
                    val target = Random.nextFloat() * 0.85f + 0.15f
                    barAmplitudes[i] = barAmplitudes[i] * 0.4f + target * 0.6f
                }
            }
            delay(60L)
        }
        if (!isPlaying) {
            for (i in 0 until barCount) {
                barAmplitudes[i] = 0f
            }
        }
    }

    // Exact Figma Rectangle 1 container: 200dp x 40dp, #000000, bottom corners 8dp, flush to top
    Box(
        modifier = Modifier
            .width(200.dp)
            .height(40.dp)
            .clip(islandShape)
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Circular Play/Pause Button (#44B678)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(activeGreen)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(16.dp)) {
                    val scaleX = size.width / 24f
                    val scaleY = size.height / 24f

                    if (!isPlaying) {
                        val offsetX = 1f * scaleX
                        val path = Path().apply {
                            moveTo(offsetX + 3.91795f * scaleX, 0.529181f * scaleY)
                            cubicTo(
                                offsetX + 1.75422f * scaleX, -0.711968f * scaleY,
                                offsetX + 0f * scaleX, 0.304796f * scaleY,
                                offsetX + 0f * scaleX, 2.79837f * scaleY
                            )
                            lineTo(offsetX + 0f * scaleX, 21.1999f * scaleY)
                            cubicTo(
                                offsetX + 0f * scaleX, 23.6959f * scaleY,
                                offsetX + 1.75422f * scaleX, 24.7114f * scaleY,
                                offsetX + 3.91795f * scaleX, 23.4714f * scaleY
                            )
                            lineTo(offsetX + 20.0018f * scaleX, 14.2474f * scaleY)
                            cubicTo(
                                offsetX + 22.1663f * scaleX, 13.0059f * scaleY,
                                offsetX + 22.1663f * scaleX, 10.9943f * scaleY,
                                offsetX + 20.0018f * scaleX, 9.753f * scaleY
                            )
                            lineTo(offsetX + 3.91795f * scaleX, 0.529181f * scaleY)
                            close()
                        }
                        drawPath(path = path, color = Color.White)
                    } else {
                        val barW = 4.5f * scaleX
                        val barH = 16f * scaleY
                        val topY = (size.height - barH) / 2f
                        val radius = CornerRadius(2f * scaleX, 2f * scaleY)

                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(5.5f * scaleX, topY),
                            size = Size(barW, barH),
                            cornerRadius = radius
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(14f * scaleX, topY),
                            size = Size(barW, barH),
                            cornerRadius = radius
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 10-bar Real-Time Equalizer inside the 200x40 top island
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                            progress = newProgress
                            val duration = exoPlayer.duration
                            if (duration > 0) {
                                exoPlayer.seekTo((duration * newProgress).toLong())
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                            progress = newProgress
                            val duration = exoPlayer.duration
                            if (duration > 0) {
                                exoPlayer.seekTo((duration * newProgress).toLong())
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (index in 0 until barCount) {
                    val baseH = baseHeights[index]
                    val amp = barAmplitudes[index]

                    val targetHeightDp = if (isPlaying) {
                        (6f + (baseH * 0.35f) + (amp * 16f)).coerceIn(6f, 26f).dp
                    } else {
                        baseH.dp
                    }

                    val animatedHeight by animateDpAsState(
                        targetValue = targetHeightDp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "eq_bar_$index"
                    )

                    val barThreshold = (index + 0.5f) / barCount.toFloat()
                    val barColor = if (progress >= barThreshold) activeGreen else inactiveGray

                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(animatedHeight)
                            .clip(RoundedCornerShape(10.dp))
                            .background(barColor)
                    )
                }
            }
        }
    }
}
