package com.intermedia.android.testaudiorecord

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.intermedia.android.testaudiorecord.ui.theme.TestAudioRecordTheme
import kotlin.math.sqrt

data class VolumePoint(
    val amplitude: Float,
    val timestamp: Long
)

class AudioEffectsChecker {
    companion object {
        private const val TAG = "AudioEffectsChecker"

        fun checkAudioEffects(sessionId: Int) {
            // Check Automatic Gain Control
            if (AutomaticGainControl.isAvailable()) {
                try {
                    val agc = AutomaticGainControl.create(sessionId)
                    Log.d(TAG, "AGC default enabled: ${agc.enabled}")
                    agc.release()
                } catch (e: Exception) {
                    Log.e(TAG, "AGC error: ${e.message}")
                }
            }

            // Check Acoustic Echo Canceler
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    val aec = AcousticEchoCanceler.create(sessionId)
                    Log.d(TAG, "AEC default enabled: ${aec.enabled}")
                    aec.release()
                } catch (e: Exception) {
                    Log.e(TAG, "AEC error: ${e.message}")
                }
            }

            // Check Noise Suppressor
            if (NoiseSuppressor.isAvailable()) {
                try {
                    val ns = NoiseSuppressor.create(sessionId)
                    Log.d(TAG, "NS default enabled: ${ns.enabled}")
                    ns.release()
                } catch (e: Exception) {
                    Log.e(TAG, "NS error: ${e.message}")
                }
            }

            // Try other effects
            try {
                val eq = Equalizer(0, sessionId)
                Log.d(TAG, "EQ default enabled: ${eq.enabled}")
                eq.release()
            } catch (e: Exception) {
                Log.e(TAG, "EQ error: ${e.message}")
            }

            try {
                val le = LoudnessEnhancer(sessionId)
                Log.d(TAG, "LE default enabled: ${le.enabled}")
                le.release()
            } catch (e: Exception) {
                Log.e(TAG, "LE error: ${e.message}")
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var volumeLevel = mutableStateOf(0f)
    private var audioSource = mutableStateOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    private lateinit var audioManager: AudioManager
    private var volumeHistory = mutableStateOf<List<VolumePoint>>(emptyList())
    private val maxHistorySize = 300 // 30 seconds at 10 samples per second


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        enableEdgeToEdge()

        setContent {
            TestAudioRecordTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AudioRecordingScreen(
                        modifier = Modifier.padding(innerPadding),
                        volumeLevel = volumeLevel.value,
                        volumeHistory = volumeHistory.value,
                        isRecording = isRecording,
                        isSpeakerOn = audioManager.isSpeakerphoneOn,
                        onRecordingToggle = { toggleRecording() },
                        onSourceToggle = { toggleAudioSource() }
                    )
                }
            }
        }
    }

    private fun toggleAudioSource() {
        // Switch audio routing
        if (audioManager.isSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = false
        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
            volumeHistory.value = emptyList()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioRecord = AudioRecord(
            audioSource.value,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        AudioEffectsChecker.checkAudioEffects(audioRecord.audioSessionId)

        audioRecord.startRecording()
        isRecording = true

        recordingThread = Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val sum = buffer.take(read).sumOf { it * it.toLong() }
                    val amplitude = sqrt(sum.toDouble() / read)
                    val normalizedAmplitude = (amplitude / Short.MAX_VALUE).toFloat() * 10

                    volumeLevel.value = normalizedAmplitude
                    volumeHistory.value = (volumeHistory.value + VolumePoint(
                        amplitude = normalizedAmplitude,
                        timestamp = System.currentTimeMillis()
                    )).takeLast(maxHistorySize)

                    Thread.sleep(100) // Sample every 100ms
                }
            }
        }.apply { start() }

        this.audioRecord = audioRecord
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        volumeLevel.value = 0f
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }
}

@Composable
fun AudioRecordingScreen(
    modifier: Modifier = Modifier,
    volumeLevel: Float,
    volumeHistory: List<VolumePoint>,
    isRecording: Boolean,
    isSpeakerOn: Boolean,
    onRecordingToggle: () -> Unit,
    onSourceToggle: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        AmplitudeGraph(
            modifier = Modifier.padding(16.dp),
            volumeHistory = volumeHistory,
            currentVolume = volumeLevel
        )

        Button(onClick = onRecordingToggle) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }

        Button(onClick = onSourceToggle) {
            Text(
                if (isSpeakerOn) "Current: Speaker"
                else "Current: Earpiece"
            )
        }
    }
}
@Composable
fun AmplitudeGraph(
    modifier: Modifier = Modifier,
    volumeHistory: List<VolumePoint>,
    currentVolume: Float
) {
    val graphColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        if (volumeHistory.isEmpty()) return@Canvas

        val path = Path()
        val strokeWidth = 2.dp.toPx()

        // Calculate points
        val points = volumeHistory.mapIndexed { index, point ->
            Offset(
                x = size.width * (index.toFloat() / volumeHistory.size),
                y = size.height * (1f - point.amplitude)
            )
        }

        // Draw the path
        if (points.isNotEmpty()) {
            path.moveTo(points.first().x, points.first().y)
            points.forEach { point ->
                path.lineTo(point.x, point.y)
            }
        }

        drawPath(
            path = path,
            color = graphColor,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
