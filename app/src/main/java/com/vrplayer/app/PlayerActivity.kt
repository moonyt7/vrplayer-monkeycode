package com.vrplayer.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.vrplayer.app.databinding.ActivityPlayerBinding
import com.vrplayer.app.gl.VrRenderer
import com.vrplayer.app.smb.SmbDataSource

@UnstableApi
class PlayerActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var renderer: VrRenderer
    private val orientation = ViewOrientation()
    private var player: ExoPlayer? = null
    private var smbFactory: SmbDataSource.Factory? = null
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private var userSeeking = false
    private var controlsVisible = true
    private var sensorsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!readyForGyro()) return
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                onLandscapeReady()
            }
        })

        renderer = VrRenderer(orientation) { surface ->
            runOnUiThread { attachPlayer(surface) }
        }
        binding.glView.setRenderer(renderer)
        binding.glView.onDrag = { dx, dy -> orientation.addDrag(dx, dy) }
        binding.glView.onTap = { toggleControls() }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val projectionAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.projection_modes,
            android.R.layout.simple_spinner_item
        )
        projectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProjection.adapter = projectionAdapter
        binding.spinnerProjection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderer.projectionMode = ProjectionMode.fromIndex(position)
                scheduleHide()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnReset.setOnClickListener { resetView() }
        binding.btnFixed.setOnClickListener { toggleFixed() }
        binding.btnSplit.setOnClickListener { toggleSplit() }
        setupIpd()
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                val p = player ?: return
                val dur = p.duration
                if (dur > 0) {
                    p.seekTo(dur * (seekBar?.progress ?: 0) / 1000)
                }
                scheduleHide()
            }
        })
        applySplitLabel()
        enterImmersive()
        scheduleHide()
        handler.post(progressTick)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.glView.onResume()
        if (readyForGyro()) onLandscapeReady()
        player?.play()
        enterImmersive()
    }

    override fun onPause() {
        player?.pause()
        stopSensors()
        binding.glView.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        smbFactory?.release()
        smbFactory = null
        renderer.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (readyForGyro()) onLandscapeReady()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!sensorsReady) return
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }
        val rotation = displayRotation()
        if (rotation != Surface.ROTATION_90 && rotation != Surface.ROTATION_270) return
        orientation.setDisplayRotation(rotation)
        orientation.onSensorVector(event.values)
    }

    private fun onLandscapeReady() {
        val rotation = displayRotation()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            orientation.setDisplayRotation(rotation)
        }
        startSensors()
    }

    private fun startSensors() {
        if (sensorsReady) return
        val sensor = rotationSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        sensorsReady = true
    }

    private fun stopSensors() {
        if (!sensorsReady) return
        sensorManager?.unregisterListener(this)
        sensorsReady = false
    }

    private fun readyForGyro(): Boolean {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        if (binding.root.width <= binding.root.height) return false
        val rotation = displayRotation()
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun attachPlayer(surface: Surface) {
        if (isDestroyed) {
            surface.release()
            return
        }
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(smbLoadControl())
            .build()
        player = exo
        exo.setVideoSurface(surface)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exo.pause()
                    showControls()
                }
                refreshPlayButton()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshPlayButton()
                if (isPlaying) scheduleHide() else {
                    handler.removeCallbacks(hideRunnable)
                    showControls()
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                renderer.videoRotationDegrees = (videoSize.unappliedRotationDegrees / 90) * 90
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.error_decode) + "：" + (error.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        })
        val smb = SmbTarget.from(intent)
        val stream = StreamRequest.from(intent)
        val local = intent.getStringExtra(EXTRA_URI)
        try {
            when {
                smb != null -> {
                    val factory = SmbDataSource.Factory(smb)
                    smbFactory = factory
                    val source = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse("smb://${smb.host}/${smb.share}/${smb.path}")))
                    exo.setMediaSource(source)
                }
                stream != null -> {
                    exo.setMediaSource(streamSource(stream))
                }
                !local.isNullOrBlank() -> {
                    exo.setMediaItem(MediaItem.fromUri(Uri.parse(local)))
                }
                else -> {
                    Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
            }
            exo.prepare()
            exo.playWhenReady = true
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_file) + "：" + e.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun streamSource(req: StreamRequest): androidx.media3.exoplayer.source.MediaSource {
        val headers = linkedMapOf<String, String>()
        if (req.referer.isNotBlank()) {
            headers["Referer"] = req.referer
            headers["Origin"] = originOf(req.referer)
        }
        if (req.cookie.isNotBlank()) headers["Cookie"] = req.cookie
        val ua = req.userAgent.ifBlank { DEFAULT_UA }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.fromUri(Uri.parse(req.url))
        return DefaultMediaSourceFactory(http).createMediaSource(item)
    }

    private fun originOf(referer: String): String {
        val uri = Uri.parse(referer)
        val scheme = uri.scheme ?: return referer
        val host = uri.host ?: return referer
        val port = uri.port
        return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
    }

    private fun smbLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !userSeeking) {
                val dur = p.duration.coerceAtLeast(0)
                val pos = p.currentPosition.coerceAtLeast(0)
                if (dur > 0) {
                    binding.seekBar.progress = ((pos * 1000) / dur).toInt()
                }
                binding.txtTime.text = "${fmt(pos)} / ${fmt(dur)}"
            }
            handler.postDelayed(this, 400)
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        scheduleHide()
    }

    private fun resetView() {
        orientation.recenter()
        scheduleHide()
    }

    private fun toggleFixed() {
        orientation.lockView(!orientation.isFixed)
        binding.txtFixedHint.visibility = if (orientation.isFixed) View.VISIBLE else View.GONE
        binding.btnFixed.alpha = if (orientation.isFixed) 1f else 0.7f
        scheduleHide()
    }

    private fun toggleSplit() {
        renderer.splitScreen = !renderer.splitScreen
        applySplitLabel()
        scheduleHide()
    }

    private fun applySplitLabel() {
        binding.btnSplit.alpha = if (renderer.splitScreen) 1f else 0.6f
    }

    private fun refreshPlayButton() {
        val playing = player?.isPlaying == true
        binding.btnPlay.setText(if (playing) R.string.pause else R.string.play)
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        setBarsVisible(true)
        if (orientation.isFixed) {
            binding.txtFixedHint.visibility = View.VISIBLE
        }
        scheduleHide()
    }

    private fun hideControls() {
        if (player?.isPlaying != true) return
        controlsVisible = false
        setBarsVisible(false)
        enterImmersive()
    }

    private fun setupIpd() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mm = prefs.getInt(KEY_IPD_MM, DEFAULT_IPD_MM).coerceIn(IPD_MIN_MM, IPD_MAX_MM)
        binding.seekIpd.max = IPD_MAX_MM - IPD_MIN_MM
        binding.seekIpd.progress = mm - IPD_MIN_MM
        applyIpd(mm, persist = false)
        binding.seekIpd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyIpd(IPD_MIN_MM + progress, persist = false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                applyIpd(IPD_MIN_MM + (seekBar?.progress ?: 0), persist = true)
                scheduleHide()
            }
        })
    }

    private fun applyIpd(mm: Int, persist: Boolean) {
        renderer.ipdMm = mm
        binding.txtIpd.text = getString(R.string.ipd_value, mm)
        if (persist) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_IPD_MM, mm).apply()
        }
    }

    private fun setBarsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.topBar.visibility = v
        binding.bottomBar.visibility = v
        binding.ipdBar.visibility = v
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player?.isPlaying == true) {
            handler.postDelayed(hideRunnable, HIDE_MS)
        }
    }

    private fun enterImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        val m = s / 60
        val r = s % 60
        return "%02d:%02d".format(m, r)
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val HIDE_MS = 3000L
        private const val PREFS = "player"
        private const val KEY_IPD_MM = "ipd_mm"
        private const val DEFAULT_IPD_MM = 64
        private const val IPD_MIN_MM = 50
        private const val IPD_MAX_MM = 80
    }
}
