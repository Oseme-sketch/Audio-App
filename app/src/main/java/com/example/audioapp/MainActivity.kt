package com.example.audioapp

import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.audioapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
private const val BUFFER_SIZE_FACTOR = 2
private const val LOG_TAG = "MainActivity"
private const val RECORD_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val REQUEST_CODE = 200
private const val SAMPLE_RATE = 44100
private const val TRACK_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val recordBufferSize: Int = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        RECORD_CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private val trackBufferSize: Int = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        TRACK_CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private var audioFilePath: String = ""
    private var hasPermission: Boolean = false
    private var isRecording: Boolean = false
    private var permissions: Array<String> = arrayOf(RECORD_AUDIO)
    private var recorder: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.audioButtonView.setOnClickListener {
            when (isRecording) {
                true -> {
                    stopRecording()
                    lifecycleScope.launch {
                        playAudio()
                    }
                }
                false -> {
                    startRecording()
                }
            }
        }

        hasPermission = (ActivityCompat.checkSelfPermission(this, permissions[0]) ==
                PackageManager.PERMISSION_GRANTED)
        if (!hasPermission) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
        }

        audioFilePath = "${externalCacheDir?.absolutePath}/audio_record.pcm"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        hasPermission = if (requestCode == REQUEST_CODE) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    override fun onStop() {
        super.onStop()

        isRecording = false

        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(
                this,
                permissions[0]
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
            return
        }
        recorder = AudioRecord(
            AUDIO_SOURCE,
            SAMPLE_RATE,
            RECORD_CHANNEL_CONFIG,
            AUDIO_FORMAT,
            recordBufferSize
        )

        if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Error initializing AudioRecord")
            return
        }

        recorder!!.startRecording()

        isRecording = true

        binding.audioButtonView.setImageResource(R.drawable.ic_baseline_mic_48)
        binding.audioButtonView.setColorFilter(
            ContextCompat.getColor(this, R.color.teal_700),
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        Log.v(LOG_TAG, "Recording started!")

        lifecycleScope.launch {
            writeAudioDataToFile()
        }
    }

    private fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null

        isRecording = false

        binding.audioButtonView.setImageResource(R.drawable.ic_baseline_mic_off_48)
        binding.audioButtonView.setColorFilter(
            ContextCompat.getColor(this, R.color.black),
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        Log.v(LOG_TAG, "Recording stopped!")
    }

    private suspend fun writeAudioDataToFile() {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(recordBufferSize / 2)
            val outputStream: FileOutputStream?
            var totalDataRead = 0

            try {
                outputStream = FileOutputStream(audioFilePath)
            } catch (e: FileNotFoundException) {
                Log.e(LOG_TAG, "AudioRecord: Audio file not found!")
                return@withContext
            }

            while (isRecording) {
                val numberOfDataRead = recorder!!.read(buffer,0,buffer.size)
                totalDataRead += numberOfDataRead

                try {
                    outputStream.write(buffer,0,numberOfDataRead)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            Log.v(LOG_TAG, "Total data read: $totalDataRead")

            try {
                outputStream.flush()
                outputStream.close()
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Exception while closing output stream")
                e.printStackTrace()
            }
        }
    }

    private suspend fun playAudio() {
        withContext(Dispatchers.IO) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AUDIO_FORMAT)
                .setChannelMask(TRACK_CHANNEL_CONFIG)
                .build()

            val player = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (player.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(LOG_TAG, "Error initializing AudioTrack")
                return@withContext
            }

            player.play()

            Log.v(LOG_TAG, "Audio streaming started!")

            val buffer = ByteArray(trackBufferSize / 2)
            val inputStream: FileInputStream?
            var totalDataWrite = 0
            var numberOfDataRead = 0

            try {
                inputStream = FileInputStream(audioFilePath)
            } catch (e: FileNotFoundException) {
                Log.e(LOG_TAG, "AudioTrack: Audio file not found!")
                return@withContext
            }

            while (numberOfDataRead != -1) {
                numberOfDataRead = inputStream.read(buffer,0,buffer.size)
                if (numberOfDataRead != -1) {
                    val numberOfDataWrite = player.write(buffer,0,numberOfDataRead)
                    totalDataWrite += numberOfDataWrite
                }
            }

            Log.v(LOG_TAG, "Total data write: $totalDataWrite")

            try {
                inputStream.close()
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Exception while closing input stream")
                e.printStackTrace()
            }

            player.apply {
                stop()
                release()
            }

            Log.v(LOG_TAG, "Audio streaming stopped!")
        }
    }
}
