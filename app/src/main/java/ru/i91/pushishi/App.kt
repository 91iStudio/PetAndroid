package ru.i91.pushishi

import android.app.Application

/** If the app ever crashes, the reason goes to the journal (the app's screen) — on the phone only, nowhere else. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { Api.journal(this, "сбой: " + e.stackTraceToString().lines().take(8).joinToString(" | ")) } catch (_: Throwable) { }
            system?.uncaughtException(thread, e)
        }
    }
}
