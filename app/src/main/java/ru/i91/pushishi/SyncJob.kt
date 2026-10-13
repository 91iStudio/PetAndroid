package ru.i91.pushishi

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory

/**
 * One trip to the site, done by the system when there is a network: strokes go out, the pet's state comes back,
 * and the picture sheet is downloaded only when it changed. Nothing runs between trips.
 */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            PetWidget.safe(applicationContext) { Sync.run(applicationContext) }
            PetWidget.safe(applicationContext) { PetWidget.refresh(applicationContext) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        /** Ask the system for a trip as soon as there is a network (one at a time). */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            // a refused trip must not bring the app down: the next wake-up will ask again
            try { js.schedule(
                JobInfo.Builder(1, ComponentName(ctx, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(10 * 60 * 1000L)
                    .build()
            ) } catch (e: Exception) { Api.journal(ctx, "система не дала запланировать поход на сайт: ${e.javaClass.simpleName}") }
        }
    }
}

object Sync {
    /** Frames are cut this tall: plenty for a 2×2 widget, light to keep. */
    private const val FRAME_HEIGHT = 300

    @Synchronized
    fun run(ctx: Context): Boolean {
        val store = Store(ctx)
        val token = store.token ?: return false
        // strokes and warmth go to the pet they were given to
        for (petId in store.pending()) {
            val strokes = store.strokes(petId)
            val warm = store.warm(petId)
            if (Api.events(ctx, token, petId, strokes, warm).ok) { store.sent(petId, strokes, warm); store.lastSent = System.currentTimeMillis() }
        }
        // one trip per choice the widgets show (several widgets of the same pet share it), and the app's own
        val slots = linkedSetOf(Store.DEFAULT)
        for (id in PetWidget.ids(ctx)) slots += store.slotOf(id)
        store.keepOnly(slots)
        for (slot in slots) {
            val petId = slot.substringBeforeLast('.').takeIf { it != "-" }
            val baby = when (slot.substringAfterLast('.')) { "b" -> true; "a" -> false; else -> null }
            val r = Api.pet(ctx, token, petId, baby)
            if (r.status == 401) { store.forget(); return false } // unpaired on the site («Сменить ключ»)
            val json = r.json ?: continue
            if (!r.ok) continue
            store.setReply(slot, json)
            val sheet = json.optJSONObject("pet")?.optString("sheet")?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            if (sheet == store.sheetUrl(slot) && store.hasFrames(slot)) continue
            val bytes = Api.download(ctx, sheet) ?: continue
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val p = json.getJSONObject("pet")
            store.saveFrames(slot, Art.cut(bmp, p.optInt("cols", 4), p.optInt("rows", 3), FRAME_HEIGHT))
            bmp.recycle()
            store.setSheetUrl(slot, sheet)
        }
        return true
    }
}
