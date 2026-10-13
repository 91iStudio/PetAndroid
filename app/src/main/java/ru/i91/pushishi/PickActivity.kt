package ru.i91.pushishi

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Whom one widget shows: opened when the widget is put on the screen, and later by a long press → «Настроить» (or from
 * the widget settings). Leaving without a choice keeps the widget: it shows whom the game shows.
 */
class PickActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // the widget stays even if the person just goes back
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        val store = Store(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
        }.also { col.addView(it) }
        val petNow = store.widgetPet(id)
        val babyNow = store.widgetBaby(id)
        fun choice(label: String, petId: String?, baby: Boolean?) = Button(this).apply {
            text = if (petId == petNow && baby == babyNow) "✓  $label" else label
            isAllCaps = false
            setOnClickListener { pick(petId, baby) }
        }.also { col.addView(it) }

        text("Кого показывает этот виджет", 20f, bold = true)
        choice("Кого показывает игра (сменится вместе с ней)", null, null)
        val list = store.lastReply?.optJSONObject("pets")?.optJSONArray("list")
        if (store.token == null || list == null) text("Откройте игру — и здесь появятся ваши питомцы.", 14f)
        else for (i in 0 until list.length()) {
            val x = list.getJSONObject(i)
            val petId = x.optString("id")
            if (x.optBoolean("canBeBaby")) {
                choice(x.optString("name") + " — взрослым", petId, false)
                choice(x.optString("name") + " — малышом", petId, true)
            } else choice(WidgetsActivity.label(x), petId, null)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
    }

    private fun pick(petId: String?, baby: Boolean?) {
        Store(this).setWidget(id, petId, baby)
        val ctx = applicationContext
        Thread { PetWidget.safe(ctx) { Sync.run(ctx); PetWidget.refresh(ctx) } }.start()
        PetWidget.refresh(this)
        finish()
    }
}
