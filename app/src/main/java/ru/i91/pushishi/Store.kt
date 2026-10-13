package ru.i91.pushishi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What the app keeps on the phone: its key, whom each widget shows, the last answers of the site, the pets' frames,
 * strokes not sent yet.
 *
 * A «slot» is one choice of whom to show: «-.s» — the pet chosen in the app (or on the site), «<id>.b» — that pet as a
 * baby, «<id>.a» — grown up, «<id>.s» — as on the site. Widgets showing the same choice share its answer and frames.
 */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    /** A fingerprint of the game's key the widgets were linked with (the key itself stays in the game). */
    var linkedWith: String?
        get() = prefs.getString("linkedWith", null)
        set(v) = prefs.edit().putString("linkedWith", v).apply()

    // ───── Whom each widget shows ─────

    fun widgetPet(id: Int): String? = prefs.getString("w$id.pet", null)
    fun widgetBaby(id: Int): Boolean? = when (prefs.getInt("w$id.baby", -1)) { 0 -> false; 1 -> true; else -> null }
    fun setWidget(id: Int, petId: String?, baby: Boolean?) =
        prefs.edit().putString("w$id.pet", petId).putInt("w$id.baby", when (baby) { false -> 0; true -> 1; null -> -1 }).apply()
    fun dropWidget(id: Int) = prefs.edit().remove("w$id.pet").remove("w$id.baby").apply()

    fun slotOf(widgetId: Int) = slot(widgetPet(widgetId), widgetBaby(widgetId))

    // ───── The site's answers, one per slot ─────

    fun reply(slot: String): JSONObject? = prefs.getString("reply:$slot", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    fun setReply(slot: String, v: JSONObject) = prefs.edit().putString("reply:$slot", v.toString()).putLong("replyAt:$slot", System.currentTimeMillis()).apply()
    fun replyAt(slot: String): Long = prefs.getLong("replyAt:$slot", 0)

    /** The answer for the app's own screen: whom it shows (the list of pets comes with every answer). */
    val lastReply: JSONObject? get() = reply(DEFAULT)

    /** Which sheet the frames of a slot were cut from. */
    fun sheetUrl(slot: String): String? = prefs.getString("sheet:$slot", null)
    fun setSheetUrl(slot: String, v: String?) = prefs.edit().putString("sheet:$slot", v).apply()

    // ───── Strokes and warmth not sent yet, per pet ─────

    fun strokes(petId: String) = prefs.getInt("strokes:$petId", 0)
    fun warm(petId: String) = prefs.getInt("warm:$petId", 0)
    fun addStroke(petId: String) = prefs.edit().putInt("strokes:$petId", strokes(petId) + 1).apply()
    fun addWarm(petId: String) = prefs.edit().putInt("warm:$petId", warm(petId) + 1).apply()
    fun sent(petId: String, strokes: Int, warm: Int) = prefs.edit()
        .putInt("strokes:$petId", maxOf(0, strokes(petId) - strokes)).putInt("warm:$petId", maxOf(0, warm(petId) - warm)).apply()
    /** The pets with something to send. */
    fun pending(): Set<String> = prefs.all.keys.filter { (it.startsWith("strokes:") || it.startsWith("warm:")) && prefs.getInt(it, 0) > 0 }
        .map { it.substringAfter(':') }.toSet()

    var lastSent: Long
        get() = prefs.getLong("sent", 0)
        set(v) = prefs.edit().putLong("sent", v).apply()

    // ───── Frames, one folder per slot ─────

    private fun framesDir(slot: String) = File(File(ctx.filesDir, "frames"), slot).apply { mkdirs() }

    fun saveFrames(slot: String, frames: List<Bitmap>) {
        val dir = framesDir(slot)
        dir.listFiles()?.forEach { it.delete() }
        frames.forEachIndexed { i, b -> File(dir, "$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        prefs.edit().putInt("framesV:$slot", FRAMES_V).apply()
    }

    fun frame(slot: String, i: Int): Bitmap? = File(framesDir(slot), "$i.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Frames cut by an older version are cut again (the format changed). */
    fun hasFrames(slot: String) = File(framesDir(slot), "0.png").exists() && prefs.getInt("framesV:$slot", 1) == FRAMES_V

    /** Slots no widget shows any more are forgotten (their frames too). */
    fun keepOnly(slots: Set<String>) {
        val e = prefs.edit()
        for (k in prefs.all.keys) {
            val s = k.substringAfter(':', "")
            if ((k.startsWith("reply:") || k.startsWith("replyAt:") || k.startsWith("sheet:") || k.startsWith("framesV:")) && s !in slots) e.remove(k)
        }
        e.apply()
        File(ctx.filesDir, "frames").listFiles()?.forEach { if (it.isFile || it.name !in slots) it.deleteRecursively() }
    }

    /** «Отвязать»: the key, the pets and their pictures are forgotten (the journal stays — it is yours). */
    fun forget() {
        prefs.edit().clear().apply()
        File(ctx.filesDir, "frames").deleteRecursively()
    }

    companion object {
        const val DEFAULT = "-.s"
        private const val FRAMES_V = 3 // 3: cut along truly empty columns

        fun slot(petId: String?, baby: Boolean?) = (petId ?: "-") + "." + when (baby) { true -> "b"; false -> "a"; null -> "s" }

        fun fingerprint(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
