package uz.aintiradar.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*

/**
 * Haydash muhiti va tezlikka moslashuvchi (Context-Aware) Media Player Service.
 *
 * Imkoniyatlari:
 * 1. Dinamik Ducking: Radar/AI ovozli ogohlantirishi chiqqanda musiqani avtomatik 15% gacha pasaytirish.
 * 2. Speed-Sensitive Volume Control (SSVC): Avtomobil tezligi ortgani sari salon shovqinini yengish
 *    uchun musiqani dinamik ko'tarib borish.
 * 3. Audio Focus boshqaruvi: Boshqa navigatsiya va qo'ng'iroqlarga to'g'ri integratsiya.
 */
class ContextAwareMediaService : MediaSessionService() {

    companion object {
        private const val CHANNEL_ID = "ainti_radar_media_channel"
        private const val NOTIFICATION_ID = 3001

        private const val DUCKED_VOLUME = 0.18f          // Ogohlantirish paytida musiqa kuchi (18%)
        private const val SPEED_VOLUME_FACTOR = 0.25f    // Tezlikka bog'liq kuchayish koeffitsienti
        private const val REFERENCE_SPEED_KMH = 120.0f   // Maksimal hisobiy tezlik
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var baseVolume: Float = 0.8f
    private var currentSpeedKmH: Float = 0.0f
    private var isDucked: Boolean = false
    private var fadeJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initializePlayer()
        setupMediaSession()
        createNotificationChannel()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        val audioAttributes = Media3AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // Media3 avtomatik audio focusni o'zi boshqaradi
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = baseVolume
            }
    }

    private fun setupMediaSession() {
        val p = player ?: return
        mediaSession = MediaSession.Builder(this, p).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * AI yoki Radar tizimidan kelgan ogohlantirish signali:
     * Ovozni pasaytirish (Ducking) yoki asl holiga qaytarish (Fade-in).
     */
    fun setDuckingEnabled(enabled: Boolean) {
        if (isDucked == enabled) return
        isDucked = enabled

        fadeJob?.cancel()
        fadeJob = serviceScope.launch {
            val targetVolume = if (isDucked) {
                DUCKED_VOLUME
            } else {
                computeSpeedCompensatedVolume(baseVolume, currentSpeedKmH)
            }
            smoothTransitionVolume(targetVolume, durationMs = 300L)
        }
    }

    /**
     * OBD-II yoki GPS dan real vaqtda tezlik o'zgarganda chaqiriladi.
     * Speed-Sensitive Volume Control (SSVC)
     */
    fun updateVehicleSpeed(speedKmH: Float) {
        this.currentSpeedKmH = speedKmH
        if (!isDucked) {
            val compensated = computeSpeedCompensatedVolume(baseVolume, currentSpeedKmH)
            player?.volume = compensated
        }
    }

    /**
     * Tezlik oshgan sari avtomobil salonidagi shamol va shina shovqini
     * eksponentsial oshadi. Shuning uchun ovoz dinamik kompensatsiya qilinadi.
     */
    private fun computeSpeedCompensatedVolume(base: Float, speed: Float): Float {
        val speedRatio = (speed / REFERENCE_SPEED_KMH).coerceIn(0.0f, 1.2f)
        val boosted = base * (1.0f + (speedRatio * SPEED_VOLUME_FACTOR))
        return boosted.coerceIn(0.0f, 1.0f)
    }

    /**
     * Musiqa ovozini keskin uzmasdan, quloqqa yoqimli tarzda silliq pasaytirish/ko'tarish
     */
    private suspend fun smoothTransitionVolume(targetVolume: Float, durationMs: Long) {
        val p = player ?: return
        val startVolume = p.volume
        val steps = 15
        val stepDelay = durationMs / steps
        val volumeDelta = (targetVolume - startVolume) / steps

        for (i in 1..steps) {
            val nextVol = (startVolume + (volumeDelta * i)).coerceIn(0.0f, 1.0f)
            p.volume = nextVol
            delay(stepDelay)
        }
        p.volume = targetVolume
    }

    /**
     * O'zbekiston hududidagi kayfiyat va yo'l holatiga qarab pleylist yuklash
     */
    fun loadContextualPlaylist(mood: DriveMood) {
        val p = player ?: return
        p.clearMediaItems()

        // Misol uchun pleylist media elementlari
        val mediaItems = when (mood) {
            DriveMood.HIGHWAY_ENERGETIC -> listOf(
                MediaItem.fromUri("asset:///audio/tracks/highway_drive.mp3")
            )
            DriveMood.NIGHT_CALM -> listOf(
                MediaItem.fromUri("asset:///audio/tracks/night_ambient.mp3")
            )
            DriveMood.TRAFFIC_RELAX -> listOf(
                MediaItem.fromUri("asset:///audio/tracks/chill_traffic.mp3")
            )
        }

        p.setMediaItems(mediaItems)
        p.prepare()
        p.play()
    }

    enum class DriveMood {
        HIGHWAY_ENERGETIC,
        NIGHT_CALM,
        TRAFFIC_RELAX
    }

    override fun onDestroy() {
        fadeJob?.cancel()
        serviceJob.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AIntiRADAR Media Player",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
