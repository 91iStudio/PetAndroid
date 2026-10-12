package ru.i91.pushishi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** What the app keeps on the phone: its key, the last answer of the site, the pet's frames, strokes not sent yet. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    /** The last /desk/pet answer (pet or egg) and when it came. */
    var lastReply: JSONObject?
        get() = prefs.getString("reply", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        set(v) = prefs.edit().putString("reply", v?.toString()).putLong("replyAt", System.currentTimeMillis()).apply()
    val replyAt: Long get() = prefs.getLong("replyAt", 0)

    /** Which sheet the frames on disk were cut from. */
    var sheetUrl: String?
        get() = prefs.getString("sheet", null)
        set(v) = prefs.edit().putString("sheet", v).apply()

    var strokes: Int
        get() = prefs.getInt("strokes", 0)
        set(v) = prefs.edit().putInt("strokes", v).apply()
    var warm: Int
        get() = prefs.getInt("warm", 0)
        set(v) = prefs.edit().putInt("warm", v).apply()
    var lastSent: Long
        get() = prefs.getLong("sent", 0)
        set(v) = prefs.edit().putLong("sent", v).apply()

    private val framesDir get() = File(ctx.filesDir, "frames").apply { mkdirs() }

    fun saveFrames(frames: List<Bitmap>) {
        framesDir.listFiles()?.forEach { it.delete() }
        frames.forEachIndexed { i, b -> File(framesDir, "$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        prefs.edit().putInt("framesV", FRAMES_V).apply()
    }

    fun frame(i: Int): Bitmap? = File(framesDir, "$i.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Frames cut by an older version are cut again (the format changed). */
    fun hasFrames() = File(framesDir, "0.png").exists() && prefs.getInt("framesV", 1) == FRAMES_V

    private companion object { const val FRAMES_V = 2 }

    /** «Отвязать»: the key, the pet and its pictures are forgotten (the journal stays — it is yours). */
    fun forget() {
        prefs.edit().clear().apply()
        framesDir.listFiles()?.forEach { it.delete() }
    }
}
