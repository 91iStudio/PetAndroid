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
            Sync.run(applicationContext)
            PetWidget.refresh(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        /** Ask the system for a trip as soon as there is a network (one at a time). */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            js.schedule(
                JobInfo.Builder(1, ComponentName(ctx, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(10 * 60 * 1000L)
                    .build()
            )
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
        val strokes = store.strokes
        val warm = store.warm
        if (strokes > 0 || warm > 0) {
            if (Api.events(ctx, token, strokes, warm).ok) {
                store.strokes = maxOf(0, store.strokes - strokes)
                store.warm = maxOf(0, store.warm - warm)
                store.lastSent = System.currentTimeMillis()
            }
        }
        val r = Api.pet(ctx, token)
        if (r.status == 401) { store.forget(); return false } // unpaired on the site («Сменить ключ»)
        val json = r.json ?: return false
        if (!r.ok) return false
        store.lastReply = json
        val sheet = json.optJSONObject("pet")?.optString("sheet")?.takeIf { it.isNotEmpty() && it != "null" }
        if (sheet != null && (sheet != store.sheetUrl || !store.hasFrames())) {
            val bytes = Api.download(ctx, sheet) ?: return true
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return true
            val p = json.getJSONObject("pet")
            store.saveFrames(Art.cut(bmp, p.optInt("cols", 4), p.optInt("rows", 3), FRAME_HEIGHT))
            bmp.recycle()
            store.sheetUrl = sheet
        }
        return true
    }
}
