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
 * A pet on the home screen. There may be several, each showing its own pet (chosen when it is put there, or later with a
 * long press → «Настроить»). It only shows what the last trip to the site brought; a few frames take turns by
 * themselves (the system flips them, the app is not running). A tap strokes the pet (or warms the egg).
 */
class PetWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        safe(ctx) { refresh(ctx) }
        SyncJob.schedule(ctx) // the system's half-hour wake-up: go and ask the site
    }

    override fun onEnabled(ctx: Context) = SyncJob.schedule(ctx)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val store = Store(ctx)
        ids.forEach { store.dropWidget(it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            TAP -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val done = goAsync()
                safe(ctx) {
                    val store = Store(ctx)
                    val reply = store.reply(store.slotOf(id))
                    val pet = reply?.optJSONObject("pet")
                    val egg = reply?.optJSONObject("egg")
                    if (pet == null && egg != null) store.addWarm(egg.optString("id"))
                    else if (pet != null) store.addStroke(pet.optString("id"))
                    showOne(ctx, id, render(ctx, store, id, tapped = true))
                    // strokes go to the site in batches, not one request per tap
                    if (System.currentTimeMillis() - store.lastSent > 30_000) SyncJob.schedule(ctx)
                }
                Handler(Looper.getMainLooper()).postDelayed({ safe(ctx) { refresh(ctx) }; done.finish() }, 2500)
            }
            PINNED -> safe(ctx) {
                // a widget put on the screen from the app: it shows the pet it was put there for
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Store(ctx).setWidget(id, intent.getStringExtra("pet"), null)
                    SyncJob.schedule(ctx)
                    refresh(ctx)
                }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val TAP = "ru.i91.pushishi.TAP"
        const val PINNED = "ru.i91.pushishi.PINNED"
        private val FRAME_IDS = intArrayOf(R.id.f0, R.id.f1, R.id.f2, R.id.f3, R.id.f4, R.id.f5)

        fun ids(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PetWidget::class.java))

        fun refresh(ctx: Context) {
            val store = Store(ctx)
            for (id in ids(ctx)) showOne(ctx, id, render(ctx, store, id, tapped = false))
        }

        /** A widget must never take the app down: whatever goes wrong is written to the journal instead. */
        fun safe(ctx: Context, block: () -> Unit) {
            try { block() } catch (e: Throwable) {
                try { Api.journal(ctx, "ошибка виджета: " + e.stackTraceToString().lines().take(6).joinToString(" | ")) } catch (_: Throwable) { }
            }
        }

        /** Asks the home screen to put one more widget there, showing this pet (the home screen asks the person first). */
        fun pin(ctx: Context, petId: String?): Boolean {
            val manager = AppWidgetManager.getInstance(ctx)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val back = PendingIntent.getBroadcast(ctx, (petId ?: "-").hashCode(),
                Intent(ctx, PetWidget::class.java).setAction(PINNED).putExtra("pet", petId),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            return manager.requestPinAppWidget(ComponentName(ctx, PetWidget::class.java), null, back)
        }

        private fun showOne(ctx: Context, id: Int, views: RemoteViews) = AppWidgetManager.getInstance(ctx).updateAppWidget(id, views)

        private fun render(ctx: Context, store: Store, id: Int, tapped: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            // the home screen may reuse the old view: what is not set again would stay from the previous picture
            v.setViewVisibility(R.id.bubble, View.GONE)
            val slot = store.slotOf(id)
            val reply = store.reply(slot)
            val pet = reply?.optJSONObject("pet")
            val egg = reply?.optJSONObject("egg")

            if (store.token == null) {
                still(v, BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher))
                bubble(v, "нажми, чтобы привязать")
                v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                return v
            }
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, PetWidget::class.java).setAction(TAP).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            when {
                pet != null && pet.optBoolean("away") -> { still(v, store.frame(slot, 6) ?: eggOf(pet, 0f)); bubble(v, "в гостях у бабушки") }
                pet != null && store.hasFrames(slot) -> {
                    val f = { i: Int -> store.frame(slot, i) }
                    when {
                        // asleep: strokes still count, but it does not wake (sleep is up to the game, the same everywhere)
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
                        val left = egg.optInt("minutesLeft") - ((System.currentTimeMillis() - store.replyAt(slot)) / 60000).toInt()
                        bubble(v, if (left <= 0) "пора вылупляться! открой игру" else "вылуплюсь через ${left / 60} ч ${left % 60} мин")
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
