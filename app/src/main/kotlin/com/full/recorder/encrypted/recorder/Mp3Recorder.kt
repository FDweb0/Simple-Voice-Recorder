package com.full.recorder.encrypted.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.full.recorder.encrypted.extensions.config
import com.full.recorder.encrypted.helpers.SAMPLE_RATE
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class Mp3Recorder(val context: Context) : Recorder {
    private var mp3buffer: ByteArray = ByteArray(0)
    private var isPaused = AtomicBoolean(false)
    private var isStopped = AtomicBoolean(false)
    private var amplitude = AtomicInteger(0)
    private var outputPath: String? = null
    private var androidLame: AndroidLame? = null
    private var fileDescriptor: FileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private val minBufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.CAMCORDER,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    )

    override fun setOutputFile(path: String) {
        outputPath = path
    }

    override fun prepare() {}

    override fun start() {
        val rawData = ShortArray(minBufferSize)
        mp3buffer = ByteArray((7200 + rawData.size * 2 * 1.25).toInt())

        outputStream = try {
            if (fileDescriptor != null) {
                FileOutputStream(fileDescriptor)
            } else {
                FileOutputStream(File(outputPath))
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return
        }

        val androidLame = LameBuilder()
            .setInSampleRate(SAMPLE_RATE)
            .setOutBitrate(context.config.bitrate / 1000)
            .setOutSampleRate(SAMPLE_RATE)
            .setOutChannels(1)
            .build()

        ensureBackgroundThread {
            audioRecord.startRecording()

            while (!isStopped.get()) {
                if (!isPaused.get()) {
                    val count = audioRecord.read(rawData, 0, minBufferSize)
                    if (count > 0) {
                        val encoded = androidLame.encode(rawData, rawData, count, mp3buffer)
                        if (encoded > 0) {
                            try {
                                updateAmplitude(rawData)
                                outputStream!!.write(mp3buffer, 0, encoded)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun stop() {
        isPaused.set(true)
        isStopped.set(true)
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        androidLame?.flush(mp3buffer)
        outputStream?.close()
    }

    override fun getMaxAmplitude(): Int {
        return amplitude.get()
    }

    override fun setOutputFile(fileDescriptor: FileDescriptor) {
        this.fileDescriptor = fileDescriptor
    }

    private fun updateAmplitude(data: ShortArray) {
        var sum = 0L
        for (i in 0 until minBufferSize step 2) {
            sum += abs(data[i].toInt())
        }
        amplitude.set((sum / (minBufferSize / 8)).toInt())
    }
}
