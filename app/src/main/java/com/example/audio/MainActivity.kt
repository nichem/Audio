package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.CheckBox
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.PermissionUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.example.audio.LocalStore.activateDB
import com.example.audio.LocalStore.minSilenceTime
import com.example.audio.LocalStore.range1
import com.example.audio.LocalStore.range2
import com.example.audio.LocalStore.saveDir
import com.example.audio.databinding.ActivityMainBinding
import com.example.audio.databinding.ItemAudioRecordBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream


class MainActivity : AppCompatActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }


    private lateinit var audioTrack: AudioTrack
    private var audioTrackBufferSize = 0
    private var recordService: RecordService? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        requestPermission()
        initAudioTrack()
        binding.btnStart.setOnClickListener {
            if (recordService?.isRecording() == true) {
                recordService?.stop()
            } else recordService?.start()
        }

        seekBarSetEvent()
        binding.rv.adapter = adapter
        adapter.setOnItemClickListener { _, _, pos ->
            val recordFileWrapper = adapter.data[pos]
            play(recordFileWrapper)
        }
        binding.btnDelete.setOnClickListener {
            val count = adapter.data.count { it.isCheck }
            if (count > 0)
                AlertDialog.Builder(this)
                    .setMessage("是否删除${count}个文件?")
                    .setPositiveButton("确认") { _, _ ->
                        adapter.delete()
                        updateList()
                    }
                    .create()
                    .show()
        }
        binding.btnDelete.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setMessage("是否删除所有文件?")
                .setPositiveButton("确认") { _, _ ->
                    adapter.allDelete()
                }
                .create()
                .show()
            true
        }
        binding.btnSettings.setOnClickListener {
            binding.layout.isGone = !binding.layout.isGone
            binding.layout2.isGone = !binding.layout2.isGone
        }
        updateList()
        updateSeekbarUI()
        binding.btnStart.text = if (recordService?.isRecording() == true) "结束" else "开始"
    }

    private fun initRecordService() {
        val intent = Intent(this, RecordService::class.java)
        startService(intent)
        bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as RecordService.MyBinder
                recordService = binder.getService()
                recordService?.callback = recordCallback
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }, Context.BIND_AUTO_CREATE)
    }

    private var allowUpdateDBUI = true
    private val recordCallback = object : RecordService.Callback {
        override fun onRecordedOne() {
            updateList()
        }

        @SuppressLint("SetTextI18n")
        override fun onDB(dB: Double) {
            if (allowUpdateDBUI) {
                allowUpdateDBUI = false
                binding.tvDB.text = "${dB.toInt()} dB"
                lifecycleScope.launch {
                    delay(200)
                    allowUpdateDBUI = true
                }
            }
        }

        @SuppressLint("SetTextI18n")
        override fun onStateChange(isRecording: Boolean) {
            binding.tvState.text = "isRecording:${isRecording}"
            binding.btnStart.text = if (isRecording) "结束" else "开始"
        }

    }

    private fun initAudioTrack() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        audioTrackBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            audioTrackBufferSize,
            AudioTrack.MODE_STREAM
        )
    }

    data class RecordFileWrapper(
        val file: File,
        var isCheck: Boolean = false,
        var isPlaying: Boolean = false
    )

    private val adapter =
        object : BaseQuickAdapter<RecordFileWrapper, BaseViewHolder>(R.layout.item_audio_record) {
            @SuppressLint("SetTextI18n")
            override fun convert(holder: BaseViewHolder, item: RecordFileWrapper) {
                val itemBinding = ItemAudioRecordBinding.bind(holder.itemView)
                itemBinding.tv.text = item.file.name
                itemBinding.tv2.text =
                    if (!item.isPlaying) "${item.file.length() / 32000L}" else progressText
                val drawable = if (!item.isPlaying) R.drawable.bg2 else R.drawable.bg2_select
                holder.itemView.setBackgroundResource(drawable)


                itemBinding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    data[holder.adapterPosition].isCheck = isChecked
                }
                itemBinding.checkbox.isChecked = item.isCheck
            }

            @SuppressLint("NotifyDataSetChanged")
            fun delete() {
                data.forEach {
                    if (it.isCheck) it.file.delete()
                }
                data.removeAll { it.isCheck }
                notifyDataSetChanged()
            }

            @SuppressLint("NotifyDataSetChanged")
            fun allDelete() {
                data.forEach {
                    it.file.delete()
                }
                data.clear()
                notifyDataSetChanged()
            }

            @SuppressLint("NotifyDataSetChanged")
            fun updatePlayUI(wrapper: RecordFileWrapper?) {
                data.forEach {
                    it.isPlaying = it === wrapper
                }
                notifyDataSetChanged()
            }

            private var progressText = ""

            fun updatePlayProgress(cur: Long, total: Long) {
                val index = data.indexOfFirst { it.isPlaying }
                if (index >= 0) {
                    progressText = "${cur / 32000}:${total / 32000}"
                    notifyItemChanged(index)
                }
            }
        }

    private fun updateList() = lifecycleScope.launch {
        val list = withContext(IO) {
            saveDir(this@MainActivity).listFiles()?.filter {
                "pcm" in it.name
            } ?: emptyList()
        }
        adapter.setList(list.map {
            RecordFileWrapper(it)
        })
    }

    private var isPlaying = false
    private fun play(recordFileWrapper: RecordFileWrapper) = lifecycleScope.launch {
        val file = recordFileWrapper.file
        adapter.updatePlayUI(recordFileWrapper)
        audioTrack.stop()
        while (isPlaying) delay(100)
        audioTrack.play()
        isPlaying = true
        launch(IO) {
            val fileInputStream = FileInputStream(file)
            val buffer = ByteArray(audioTrackBufferSize)
            var readTotal = 0L
            val total = file.length()
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val read = fileInputStream.read(buffer)
                readTotal += read
                if (read == -1) {
                    audioTrack.stop()
                    break
                }
                audioTrack.write(buffer, 0, read)
                withContext(Main) {
                    adapter.updatePlayProgress(readTotal, total)
                }
            }
            isPlaying = false
        }
    }


    @SuppressLint("SetTextI18n")
    private fun updateSeekbarUI(noUpdateProgress: Boolean = false) {
        binding.tv1.text = "最大沉默时间：${minSilenceTime}秒"
        binding.tv2.text = "激活分贝：${activateDB}DB"
        if (!noUpdateProgress) {
            binding.seekBar1.progress = minSilenceTime - range1.first
            binding.seekBar2.progress = activateDB - range2.first
        }
    }

    private fun seekBarSetEvent() {
        binding.seekBar1.max = range1.last - range1.first
        binding.seekBar1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    minSilenceTime = progress + range1.first
                    updateSeekbarUI(true)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.seekBar2.max = range2.last - range2.first
        binding.seekBar2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    activateDB = progress + range2.first
                    updateSeekbarUI(true)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

    }

    private fun requestPermission() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionUtils
            .permission(*permissions.toTypedArray())
            .callback { isAllGranted, _, _, _ ->
                if (!isAllGranted) AppUtils.exitApp()
                else initRecordService()
            }
            .request()
    }
}