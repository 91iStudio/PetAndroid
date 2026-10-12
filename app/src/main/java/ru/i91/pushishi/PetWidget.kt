package ru.i91.pushishi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * The pet on the home screen. It only shows what the last trip to the site brought; a few frames take turns by
 * themselves (the system flips them, the app is not running). A tap strokes the pet (or warms the egg).
 */
class PetWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        safe(ctx) { refresh(ctx) }
        SyncJob.schedule(ctx) // the system's half-hour wake-up: go and ask the site
    }

    override fun onEnabled(ctx: Context) = SyncJob.schedule(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TAP) return super.onReceive(ctx, intent)
        val done = goAsync()
        safe(ctx) {
            val store = Store(ctx)
            val egg = store.lastReply?.optJSONObject("egg") != null && store.lastReply?.optJSONObject("pet") == null
            if (egg) store.warm = store.warm + 1 else store.strokes = store.strokes + 1
            show(ctx, render(ctx, store, tapped = true))
            // strokes go to the site in batches, not one request per tap
            if (System.currentTimeMillis() - store.lastSent > 30_000) SyncJob.schedule(ctx)
        }
        Handler(Looper.getMainLooper()).postDelayed({ safe(ctx) { refresh(ctx) }; done.finish() }, 2500)
    }

    companion object {
        const val TAP = "ru.i91.pushishi.TAP"
        private val FRAME_IDS = intArrayOf(R.id.f0, R.id.f1, R.id.f2, R.id.f3, R.id.f4, R.id.f5)

        fun refresh(ctx: Context) = show(ctx, render(ctx, Store(ctx), tapped = false))

        /** A widget must never take the app down: whatever goes wrong is written to the journal instead. */
        fun safe(ctx: Context, block: () -> Unit) {
            try { block() } catch (e: Throwable) {
                try { Api.journal(ctx, "ошибка виджета: " + e.stackTraceToString().lines().take(6).joinToString(" | ")) } catch (_: Throwable) { }
            }
        }

        private fun show(ctx: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, PetWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views)
        }

        private fun render(ctx: Context, store: Store, tapped: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            // the home screen may reuse the old view: what is not set again would stay from the previous picture
            v.setViewVisibility(R.id.bubble, View.GONE)
            val reply = store.lastReply
            val pet = reply?.optJSONObject("pet")
            val egg = reply?.optJSONObject("egg")

            if (store.token == null) {
                still(v, BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher))
                bubble(v, "нажми, чтобы привязать")
                v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                return v
            }
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(ctx, 1, Intent(ctx, PetWidget::class.java).setAction(TAP), PendingIntent.FLAG_IMMUTABLE))

            when {
                pet != null && pet.optBoolean("away") -> { still(v, store.frame(6) ?: eggOf(pet, 0f)); bubble(v, "в гостях у бабушки") }
                pet != null && store.hasFrames() -> {
                    val f = { i: Int -> store.frame(i) }
                    when {
                        // asleep: strokes still count, but it does not wake (sleep is up to the site, the same everywhere)
                        pet.optBoolean("asleep") -> { still(v, f(4)); bubble(v, if (tapped) "мрр…" else "Zz…") }
                        tapped -> still(v, f(5)) // just glad: a happy face says it all
                        else -> {
                            // stand, blink, sit — the system flips them
                            val all = listOfNotNull(f(0), f(1), f(9))
                            if (all.size == 3) {
                                val (stand, blink, sit) = Art.pad(all)
                                flip(v, 1200, listOf(stand, stand, blink, stand, sit, sit))
                            } else still(v, all.firstOrNull())
                            when {
                                pet.optBoolean("hungry") -> bubble(v, "кушать хочу…")
                                pet.optBoolean("tired") -> bubble(v, "спатки…")
                                pet.optBoolean("bored") -> bubble(v, "поиграем?")
                            }
                        }
                    }
                }
                pet != null -> { still(v, eggOf(pet, 0f)); bubble(v, "${pet.optString("name", "Малыш")} уже вылупился! Картинка рисуется…") }
                egg != null -> {
                    if (tapped) still(v, eggOf(egg, 7f))
                    else {
                        flip(v, 450, listOf(eggOf(egg, -7f), eggOf(egg, 0f), eggOf(egg, 7f), eggOf(egg, 0f), eggOf(egg, 0f), eggOf(egg, 0f)))
                        val left = egg.optInt("minutesLeft") - ((System.currentTimeMillis() - store.replyAt) / 60000).toInt()
                        bubble(v, if (left <= 0) "пора вылупляться! открой сайт" else "вылуплюсь через ${left / 60} ч ${left % 60} мин")
                    }
                }
                else -> { still(v, BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)); bubble(v, reply?.optString("message")?.takeIf { it.isNotEmpty() } ?: "загружаю…") }
            }
            return v
        }

        private fun eggOf(o: JSONObject, tilt: Float): Bitmap {
            val look = o.optJSONObject("look")
            return Art.egg(200, Art.color(look?.optString("base"), Color.rgb(0xF3, 0xD9, 0xB1)), Art.color(look?.optString("mark"), Color.rgb(0x8A, 0x5A, 0x3C)), look?.optString("pattern"), tilt)
        }

        private fun still(v: RemoteViews, b: Bitmap?) {
            v.setViewVisibility(R.id.flipper, View.GONE)
            v.setViewVisibility(R.id.still, View.VISIBLE)
            if (b != null) v.setImageViewBitmap(R.id.still, Art.pad(listOf(b))[0])
        }

        private fun flip(v: RemoteViews, interval: Int, frames: List<Bitmap?>) {
            v.setViewVisibility(R.id.still, View.GONE)
            v.setViewVisibility(R.id.flipper, View.VISIBLE)
            v.setInt(R.id.flipper, "setFlipInterval", interval)
            frames.forEachIndexed { i, b -> if (b != null) v.setImageViewBitmap(FRAME_IDS[i], b) }
        }

        private fun bubble(v: RemoteViews, text: String, color: Int = Color.rgb(0x5A, 0x3D, 0x2B)) {
            v.setViewVisibility(R.id.bubble, View.VISIBLE)
            v.setTextViewText(R.id.bubble, text)
            v.setTextColor(R.id.bubble, color)
        }
    }
}
