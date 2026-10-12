package ru.i91.pushishi

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The app's only screen: pair with the site, put the pet on the home screen, see what the app has sent. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var nonce: String? = null
    private var nonceUntil = 0L
    private var asked = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this)
        nonce = state?.getString("nonce")
        nonceUntil = state?.getLong("nonceUntil") ?: 0
        build()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("nonce", nonce)
        out.putLong("nonceUntil", nonceUntil)
    }

    override fun onResume() {
        super.onResume()
        if (nonce != null) poll() // back from the browser: has the site confirmed?
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
        }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }.also { col.addView(it) }

        text(getString(R.string.app_name), 28f, bold = true)
        val token = store.token
        if (token == null) {
            text("Ваш питомец с pet.91i.ru — на экране телефона. Привяжите приложение к своим питомцам:")
            text("1. На сайте, где живут питомцы, откройте меню → «Питомец на рабочем столе». Там 6-значный код — введите его здесь:", 15f)
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
            text("2. Или, если питомцы уже открыты в браузере этого телефона:", 15f)
            button("Открыть сайт и привязать") { startLink(status) }
        } else {
            val reply = store.lastReply
            val name = reply?.optJSONObject("pet")?.optString("name")
            text(if (name != null) "$name живёт на экране телефона." else if (reply?.optJSONObject("egg") != null) "Пока малыш в яйце — оно лежит на экране телефона. Погладьте его пальцем, чтобы согреть." else "Привязано. Сейчас спрошу сайт, как дела.")
            store.frame(0)?.let { col.addView(ImageView(this).apply { setImageBitmap(it); adjustViewBounds = true; maxHeight = dp(180) }) }
            val manager = AppWidgetManager.getInstance(this)
            if (manager.getAppWidgetIds(ComponentName(this, PetWidget::class.java)).isEmpty()) {
                if (manager.isRequestPinAppWidgetSupported) button("Поставить на главный экран") { manager.requestPinAppWidget(ComponentName(this, PetWidget::class.java), null, null) }
                text("Или: долгое нажатие на пустом месте главного экрана → «Виджеты» → «Пушиши».", 14f)
            }
            button("Обновить сейчас") {
                Thread { Sync.run(this); PetWidget.refresh(this); main.post { build() } }.start()
            }
            button("Отвязать этот телефон") {
                AlertDialog.Builder(this).setMessage("Отвязать? Питомец останется на сайте, а с телефона пропадёт.")
                    .setPositiveButton("Отвязать") { _, _ -> store.forget(); PetWidget.refresh(this); build() }
                    .setNegativeButton("Не надо", null).show()
            }
            if (reply == null && !asked) { asked = true; Thread { Sync.run(this); PetWidget.refresh(this); main.post { if (!isFinishing) build() } }.start() }
        }

        text("")
        text("Что приложение делает: раз в полчаса (когда телефон сам будит виджет) спрашивает pet.91i.ru, как дела у питомца, " +
            "и передаёт, сколько раз вы его погладили. Больше ничего не читает и никуда не отправляет. Код открыт.", 13f)
        button("Что приложение отправляло") {
            val t = TextView(this).apply { text = Api.readJournal(this@MainActivity); textSize = 11f; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
            AlertDialog.Builder(this).setTitle("Журнал").setView(ScrollView(this).apply { addView(t) }).setPositiveButton("Закрыть", null).show()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ───── Pairing through the browser: the site shows «Привязать?», the app waits for the answer ─────

    private fun startLink(status: TextView) {
        status.text = "Открываю сайт…"
        Thread {
            val n = Api.start(this).json?.optString("nonce")?.takeIf { it.isNotEmpty() }
            main.post {
                if (n == null) { status.text = "Нет связи с сайтом"; return@post }
                nonce = n; nonceUntil = System.currentTimeMillis() + 15 * 60 * 1000
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Api.SITE}/#desk=$n")))
            }
        }.start()
    }

    private fun poll() {
        val n = nonce ?: return
        if (System.currentTimeMillis() > nonceUntil) { nonce = null; return }
        Thread {
            val j = Api.poll(this, n).json
            main.post {
                val t = j?.optString("token")?.takeIf { it.isNotEmpty() }
                when {
                    t != null -> { nonce = null; Api.journal(this, "привязано через сайт"); paired(t) }
                    j?.optBoolean("expired") == true -> nonce = null
                    else -> main.postDelayed({ poll() }, 2000)
                }
            }
        }.start()
    }

    private fun paired(token: String) {
        store.token = token
        asked = false
        build()
    }
}
