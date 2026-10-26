package com.literec

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private inner class P<T : Any>(val key: String, val def: T) : ReadWriteProperty<Any?, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (def) {
            is Int -> sp.getInt(key, def) as T
            is Boolean -> sp.getBoolean(key, def) as T
            is String -> (sp.getString(key, def) ?: def) as T
            else -> def
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            val e = sp.edit()
            when (value) {
                is Int -> e.putInt(key, value)
                is Boolean -> e.putBoolean(key, value)
                is String -> e.putString(key, value)
            }
            e.apply()
        }
    }

    // Last used sources (also used by the Quick Settings tile)
    var screen by P("screen", true)
    var sys by P("sys", false)
    var mic by P("mic", false)

    // Video
    var resolution by P("resolution", 0)      // short side in px; 0 = native
    var fps by P("fps", 60)
    var bitrateMbps by P("bitrate", 0)        // 0 = auto
    var hevc by P("hevc", false)
    var orientation by P("orientation", 0)    // 0 auto, 1 portrait, 2 landscape

    // Audio
    var sampleRate by P("rate", 48000)
    var audioKbps by P("akbps", 128)
    var sysGain by P("sysgain", 100)
    var micGain by P("micgain", 100)

    // Behaviour
    var countdown by P("countdown", 3)
    var stopOnLock by P("lock", false)
    var pattern by P("pattern", "Rec_{date}_{time}")

    // Storage ("" = default MediaStore folders)
    var tree by P("tree", "")

    // Library
    var sortKey by P("sort", 0)               // 0 date, 1 size, 2 duration

    // Interface size in percent, and screen-capture prompt style (Android 14+)
    var uiScale by P("uiscale", 125)
    var fullOnly by P("fullonly", true)
}
