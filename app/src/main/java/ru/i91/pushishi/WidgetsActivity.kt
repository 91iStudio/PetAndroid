package ru.i91.pushishi

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * The widgets' settings (from the game's menu): put a pet on the home screen, choose whom each widget shows, see what the
 * app has sent. The widgets link to the game's pets by themselves; a 6-digit code from the site is the other way.
 */
class WidgetsActivity : Activity() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var asked = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this)
        title = "Виджеты"
        build()
    }

    override fun onResume() {
        super.onResume()
        build() // back from the home screen or from a widget's choice
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
        }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }.also { col.addView(it) }

        text("Пушиши на главном экране", 24f, bold = true)
        val version = packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (сборка ${it.longVersionCode})" }
        text("версия $version", 12f)
        if (store.token == null) {
            text("Виджеты привяжутся к питомцам сами, как только в игре появится хотя бы одно яйцо.")
            text("Если играете в браузере, а не здесь: на сайте откройте меню → «Питомец на рабочем столе». Там 6-значный код — введите его:", 15f)
            val code = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; hint = "000000"; textSize = 26f; gravity = Gravity.CENTER
                filters = arrayOf(InputFilter.LengthFilter(6))
            }
            col.addView(code)
            val status = TextView(this)
            button("Привязать по коду") {
                val c = code.text.toString().filter { it.isDigit() }
                if (c.length != 6) { status.text = "В коде 6 цифр"; return@button }
                status.text = "Проверяю…"
                Thread {
                    val r = Api.pair(this, c)
                    val t = r.json?.optString("token")?.takeIf { r.ok && it.isNotEmpty() }
                    main.post { if (t != null) paired(t) else status.text = r.json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Нет связи с сайтом" }
                }.start()
            }
            col.addView(status)
        } else {
            val reply = store.lastReply
            val list = reply?.optJSONObject("pets")?.optJSONArray("list")
            val ids = PetWidget.ids(this)

            // ── put a pet on the home screen ──
            text("Поставить на главный экран", 17f, bold = true)
            if (list == null || list.length() == 0) text("Сейчас спрошу игру, кто у вас живёт…", 14f)
            else for (i in 0 until list.length()) {
                val x = list.getJSONObject(i)
                button("＋ " + label(x)) { if (!PetWidget.pin(this, x.optString("id"))) howTo() }
            }
            text("Каждый виджет — свой питомец: поставьте нескольких рядом. Или долгое нажатие на пустом месте экрана → «Виджеты» → «Пушиш».", 13f)

            // ── the widgets already there ──
            if (ids.isNotEmpty()) {
                text("Уже на экране", 17f, bold = true)
                ids.forEachIndexed { n, id ->
                    val shown = store.reply(store.slotOf(id))?.let { r -> r.optJSONObject("pet")?.optString("name") ?: r.optJSONObject("egg")?.let { "яйцо" } }
                    val age = when (store.widgetBaby(id)) { true -> ", малышом"; false -> ", взрослым"; null -> "" }
                    val who = if (store.widgetPet(id) == null) "кого показывает игра" + (shown?.let { " (сейчас $it)" } ?: "") else (shown ?: "питомец") + age
                    button("Виджет ${n + 1}: $who — сменить") {
                        startActivity(Intent(this, PickActivity::class.java).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    }
                }
            }
            store.frame(Store.DEFAULT, 0)?.let { col.addView(ImageView(this).apply { setImageBitmap(it); adjustViewBounds = true; maxHeight = dp(150) }) }

            button("Обновить сейчас") {
                Thread { Sync.run(this); PetWidget.refresh(this); main.post { build() } }.start()
            }
            button("Отвязать виджеты") {
                AlertDialog.Builder(this).setMessage("Отвязать? Питомцы останутся в игре, а с главного экрана пропадут.")
                    .setPositiveButton("Отвязать") { _, _ -> store.forget(); PetWidget.refresh(this); build() }
                    .setNegativeButton("Не надо", null).show()
            }
            if (reply == null && !asked) { asked = true; Thread { Sync.run(this); PetWidget.refresh(this); main.post { if (!isFinishing) build() } }.start() }
        }

        text("")
        text("Что делают виджеты: раз в полчаса (когда телефон сам их будит) спрашивают pet.91i.ru, как дела у питомцев, " +
            "и передают, сколько раз вы их погладили. Больше ничего не читают и никуда не отправляют. Код открыт.", 13f)
        button("Что приложение отправляло") {
            val t = TextView(this).apply { text = Api.readJournal(this@WidgetsActivity); textSize = 11f; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
            AlertDialog.Builder(this).setTitle("Журнал").setView(ScrollView(this).apply { addView(t) }).setPositiveButton("Закрыть", null).show()
        }
        button("Вернуться в игру") { finish() }
        // Android 15 draws apps under the status bar: keep the text below it
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
    }

    private fun howTo() {
        AlertDialog.Builder(this).setMessage("Этот телефон не умеет ставить виджет из приложения. Долгое нажатие на пустом месте главного экрана → «Виджеты» → «Пушиш».")
            .setPositiveButton("Понятно", null).show()
    }

    private fun paired(token: String) {
        store.token = token
        asked = false
        build()
    }

    companion object {
        fun label(x: JSONObject) = x.optString("name") + when (x.optString("stage")) { "egg" -> " (яйцо)"; "baby" -> " (малыш)"; else -> "" }
    }
}
