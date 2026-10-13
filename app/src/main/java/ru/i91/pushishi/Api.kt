package ru.i91.pushishi

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app says to the internet goes through here, to one address only, and every request is written to the
 * journal (the app's screen, «Что приложение отправляло»): when, where and exactly what was sent. The key is shown masked.
 * Never call it on the main thread.
 */
object Api {
    const val SITE = "https://pet.91i.ru"

    class Reply(val status: Int, val body: String) {
        val json: JSONObject? get() = try { JSONObject(body) } catch (_: Exception) { null }
        val ok get() = status == 200
    }

    private fun send(ctx: Context, method: String, path: String, json: String?, token: String?, quiet: Boolean = false, gameKey: String? = null): Reply {
        val url = SITE + path
        val sent = "$method $url" + (if (token != null) "  [ключ приложения: ${token.take(4)}…]" else "") +
            (if (gameKey != null) "  [ключ игры этого телефона: ${gameKey.take(4)}…]" else "") +
            (if (json != null) "  отправлено: $json" else "  (ничего не отправлено, только запрос)")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            if (token != null) c.setRequestProperty("X-Desk-Token", token)
            if (gameKey != null) c.setRequestProperty("X-Pet-Key", gameKey)
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            val status = c.responseCode
            val body = (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (!quiet) journal(ctx, "$sent  → $status")
            Reply(status, body)
        } catch (e: Exception) {
            if (!quiet) journal(ctx, "$sent  → нет связи (${e.javaClass.simpleName})")
            Reply(0, "")
        }
    }

    /** The 6-digit code from the site → this phone's own key. */
    fun pair(ctx: Context, code: String) = send(ctx, "POST", "/api/desk/pair", JSONObject().put("code", code).toString(), null)

    /** A one-time link to open the site with (the player confirms there, in their own browser). */
    fun start(ctx: Context) = send(ctx, "POST", "/api/desk/start", "{}", null)

    fun poll(ctx: Context, nonce: String) = send(ctx, "GET", "/api/desk/poll?nonce=" + URLEncoder.encode(nonce, "UTF-8"), null, null, quiet = true)

    /** The game in this app links the widgets by itself: its own key confirms the one-time link, as the site would. */
    fun claim(ctx: Context, gameKey: String, nonce: String) = send(ctx, "POST", "/api/desk/claim", JSONObject().put("nonce", nonce).toString(), null, gameKey = gameKey)

    /** The pet of one widget: petId/baby — that widget's own choice (null — whom the app shows). */
    fun pet(ctx: Context, token: String, petId: String? = null, baby: Boolean? = null) = send(ctx, "GET", "/api/desk/pet" +
        (if (petId != null) "?pet=" + URLEncoder.encode(petId, "UTF-8") + (if (baby != null) "&baby=$baby" else "") else ""), null, token)

    /** Whom this phone shows (null — as on the site) and how old (null — as on the site). */
    fun choose(ctx: Context, token: String, petId: String?, baby: Boolean?) =
        send(ctx, "POST", "/api/desk/choose", JSONObject().put("petId", petId ?: JSONObject.NULL).put("baby", baby ?: JSONObject.NULL).toString(), token)

    fun events(ctx: Context, token: String, petId: String, strokes: Int, warm: Int) =
        send(ctx, "POST", "/api/desk/events", JSONObject().put("petId", petId).put("strokes", strokes).put("minutes", 0).put("warm", warm).toString(), token)

    /** The pet's picture sheet (a public PNG). */
    fun download(ctx: Context, url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        val status = c.responseCode
        journal(ctx, "GET $url  (картинка питомца)  → $status")
        val bytes = if (status == 200) c.inputStream.use { it.readBytes() } else null
        c.disconnect()
        bytes
    } catch (e: Exception) {
        journal(ctx, "GET $url  → нет связи (${e.javaClass.simpleName})")
        null
    }

    // ───── The journal: the last 300 lines, on the phone only ─────

    private fun journalFile(ctx: Context) = File(ctx.filesDir, "journal.txt")

    @Synchronized
    fun journal(ctx: Context, line: String) {
        val f = journalFile(ctx)
        val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date())
        val lines = (if (f.exists()) f.readLines() else emptyList()) + "$stamp  $line"
        f.writeText(lines.takeLast(300).joinToString("\n") + "\n")
    }

    fun readJournal(ctx: Context): String {
        val f = journalFile(ctx)
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else "Пока ничего не отправляли."
    }
}
