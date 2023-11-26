package com.example.audio

import android.content.Context
import com.tencent.mmkv.MMKV
import java.io.File

object LocalStore {
    var minSilenceTime: Int
        get() {
            return MMKV.defaultMMKV().decodeInt("minSilenceTime", 10)
        }
        set(value) {
            MMKV.defaultMMKV().encode("minSilenceTime", value)
        }
    var range1 = 5..30
    var activateDB: Int
        get() {
            return MMKV.defaultMMKV().decodeInt("activateDB", 50)
        }
        set(value) {
            MMKV.defaultMMKV().encode("activateDB", value)
        }
    var range2 = 45..80

    fun saveDir(context: Context) = File(context.filesDir, "cache").apply {
        if (!exists()) mkdirs()
    }
}