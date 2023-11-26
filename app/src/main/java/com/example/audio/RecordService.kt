package com.example.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.audio.LocalStore.activateDB
import com.example.audio.LocalStore.minSilenceTime
import com.example.audio.LocalStore.saveDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar


class RecordService : Service() {
    private val scope = CoroutineScope(Main)
    private lateinit var audioRecord: AudioRecord
    fun isRecording() = audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
    private var audioRecordBufferSize: Int = 0
    private var isWritingFile = false
    private var fileOutputStream: FileOutputStream? = null
    private var job: Job? = null
    private lateinit var saveDir: File
    override fun onBind(intent: Intent?): IBinder {
        return MyBinder()
    }

    override fun onCreate() {
        super.onCreate()
        initAudioRecord()
        saveDir = saveDir(this)
        createChannel()
        recordNotify(false)
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        Log.d("test", "+++++++")
        val sampleRate = 16000 // 采样率
        val channelConfig = AudioFormat.CHANNEL_IN_MONO // 单声道
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16位PCM编码
        audioRecordBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            audioRecordBufferSize
        )
    }

    @SuppressLint("SetTextI18n")
    fun start() = scope.launch {
        recordNotify(true)
        val buffer = ByteArray(audioRecordBufferSize)
        audioRecord.startRecording()
        callback?.onStateChange(true)
        launch(Dispatchers.IO) {
            while (isRecording()) {
                val bytesRead = audioRecord.read(buffer, 0, audioRecordBufferSize)
                if (bytesRead > 0) {
                    try {
                        val dB = calculateDB(buffer)
                        if (dB > activateDB.toDouble()) {
                            Log.d("test", "大于$activateDB")
                            job?.cancel()
                            if (!isWritingFile) {
                                launch(Dispatchers.IO) {
                                    val name = "${nowDate()}.pcm"
                                    Log.d("test", "新录音$name")
                                    isWritingFile = true
                                    val file = File(saveDir, name)
                                    fileOutputStream = FileOutputStream(file)
                                }
                            }
                        } else {
                            if (job?.isActive != true && isWritingFile)
                                job = launch(Dispatchers.IO) {
                                    Log.d("test", "开始计时")
                                    delay(minSilenceTime * 1000L)
                                    Log.d("test", "计时完毕")
                                    fileOutputStream?.flush()
                                    fileOutputStream?.close()
                                    fileOutputStream = null
                                    isWritingFile = false
                                    withContext(Main) {
                                        callback?.onRecordedOne()
                                    }
                                }
                        }
                        try {
                            fileOutputStream?.write(buffer, 0, bytesRead)
                        } catch (e: Exception) {
                            Log.e("test", e.stackTraceToString())
                        }
                        withContext(Main) { callback?.onDB(dB) }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun stop() {
        recordNotify(false)
        audioRecord.stop()
        callback?.onStateChange(false)
    }

    private fun calculateDB(byteArray: ByteArray): Double {
        var sum = 0.0
        val shorts = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        shorts.forEach {
            sum += it * it
        }
        sum = sum / byteArray.size * 1.0
        return Math.log10(sum) * 10
    }

    private fun nowDate(): String {
        val calendar = Calendar.getInstance()

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)

        return String.format("%d-%02d-%02d_%02d-%02d-%02d", year, month, day, hour, minute, second)
    }

    var callback: Callback? = null

    interface Callback {
        fun onRecordedOne()

        fun onDB(dB: Double)

        fun onStateChange(isRecording: Boolean)
    }

    inner class MyBinder : Binder() {
        fun getService(): RecordService = this@RecordService
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音通知",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }


    }

    private fun recordNotify(isRecording: Boolean) {
        val content = if (isRecording) "正在运行" else "暂停中"
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("录音")
            .setContentText(content)
            .setSmallIcon(R.drawable.icon_mic)
            .setContentIntent(pendingIntent)
            .build()
        // 将服务设置为前台服务
        startForeground(1, notification)
    }


    override fun onDestroy() {
        Log.d("test","onDestroy")
        stopForeground(true)
//        stopSelf()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recordChannel"
    }
}