package com.myne23.abyssresolver

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arkiv.player.resolver.contract.IExternalResolver
import com.arkiv.player.resolver.contract.IExternalResolverCallback
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ResolverService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var current: AtomicBoolean? = null
    @Volatile private var server: AbyssMediaServer? = null
    @Volatile private var megaServer: MegaMediaServer? = null
    @Volatile private var resolving = false
    @Volatile private var lastResolveAt = System.currentTimeMillis()
    private val main = Handler(Looper.getMainLooper())

    private fun diagnostic(stage: String, detail: String) {
        Log.i(TAG, "$stage: $detail")
        val prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
        val time = System.currentTimeMillis()
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(time))
        val previous = prefs.getString("history", "").orEmpty()
        val entry = "$stamp  $stage: ${detail.take(3000)}"
        val history = (previous.lineSequence().filter { it.isNotBlank() } + entry)
            .toList().takeLast(20).joinToString("\n")
        prefs.edit()
            .putString("stage", stage)
            .putString("detail", detail.take(240))
            .putString("history", history)
            .putLong("time", time)
            .apply()
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val lastRead = maxOf(lastResolveAt, server?.lastActivityMs ?: 0L, megaServer?.lastActivityMs ?: 0L)
            if (!resolving && System.currentTimeMillis() - lastRead > 10 * 60 * 1000L) {
                diagnostic("service_idle_stop", "No active resolve or media requests")
                server?.stop()
                server = null
                megaServer?.stop()
                megaServer = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else main.postDelayed(this, 60_000L)
        }
    }

    private val binder = object : IExternalResolver.Stub() {
        override fun resolve(protocol: String, ref: String, callback: IExternalResolverCallback) {
            diagnostic("resolve_received", "protocol=$protocol refLength=${ref.length}")
            current?.set(true)
            val cancelled = AtomicBoolean(false)
            current = cancelled
            resolving = true
            lastResolveAt = System.currentTimeMillis()
            executor.execute {
                var finished = false
                try {
                    diagnostic("resolve_started", "protocol=$protocol")
                    promoteToForeground()
                    val local: String
                    when (protocol) {
                        "abyss" -> {
                            callback.onProgress(-1, "Preparando Abyss… / Preparing Abyss…")
                            diagnostic("abyss_resolving", "Resolving source reference")
                            val parsed = AbyssSourceResolver.resolve(ref, cancelled, diagnostic = { stage, detail -> diagnostic(stage, detail) }) { p, msg ->
                                diagnostic("abyss_progress", "$p% $msg")
                                callback.onProgress(p, msg)
                            }
                            if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
                            diagnostic("abyss_resolved", "Source resolved; registering local media endpoint")
                            val mediaServer = synchronized(this@ResolverService) {
                                server ?: AbyssMediaServer(this@ResolverService).also { server = it }
                            }
                            local = mediaServer.register(parsed)
                        }
                        "mega" -> {
                            callback.onProgress(-1, "Preparando MEGA… / Preparing MEGA…")
                            diagnostic("mega_resolving", "Resolving MEGA reference")
                            val parsed = MegaSourceResolver.resolve(ref, cancelled) { p, msg ->
                                diagnostic("mega_progress", "$p% $msg")
                                callback.onProgress(p, msg)
                            }
                            if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
                            diagnostic("mega_resolved", "MEGA metadata resolved; registering local media endpoint")
                            val mediaServer = synchronized(this@ResolverService) {
                                megaServer ?: MegaMediaServer(this@ResolverService).also { megaServer = it }
                            }
                            local = mediaServer.register(parsed)
                        }
                        else -> throw ResolveFailure("unsupported", "Protocolo no compatible / Unsupported protocol")
                    }
                    val answer = JSONObject()
                        .put("url", local)
                        .put("mime", "video/mp4")
                        .put("expiresInSeconds", 86400)
                        .toString()
                    callback.onResult(answer)
                    diagnostic("result_sent", "Result callback sent; local URL registered for $protocol")
                    finished = true
                } catch (e: ResolveFailure) {
                    diagnostic("resolve_error_${e.code}", e.message ?: "Resolver failure")
                    runCatching { callback.onError(e.code, e.message ?: "No se pudo resolver la fuente / Could not resolve source") }
                    finished = true
                } catch (e: Throwable) {
                    Log.e(TAG, "resolve failed for protocol=$protocol", e)
                    diagnostic("resolve_exception", "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                    runCatching { callback.onError("unavailable", "El proveedor no respondió o no se pudo descifrar el video / Provider did not respond or video could not be decrypted") }
                    finished = true
                } finally {
                    if (!finished) {
                        diagnostic("resolve_unfinished", "Sending generic error")
                        runCatching { callback.onError("other", "Error inesperado / Unexpected error") }
                    }
                    resolving = false
                    if (current === cancelled) current = null
                    main.removeCallbacks(idleCheck)
                    main.postDelayed(idleCheck, 60_000L)
                }
            }
        }

        override fun cancel() {
            diagnostic("cancel_received", "Kino requested cancellation")
            current?.set(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        diagnostic("service_bound", "Kino or another client bound the resolver service")
        return binder.asBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        diagnostic("service_unbound", "Client unbound the resolver service")
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diagnostic("service_started", "Foreground service start requested")
        startForegroundCompat()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 60_000L)
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(Intent(this, ResolverService::class.java))
            else startService(Intent(this, ResolverService::class.java))
        }.onFailure { diagnostic("foreground_start_error", it.message ?: it.javaClass.simpleName) }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("abyss-resolver", "Abyss Resolver", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "abyss-resolver") else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setContentTitle("Abyss Resolver")
            .setContentText("Sirviendo video a Kino / Serving video to Kino")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(7, notification)
    }

    override fun onDestroy() {
        diagnostic("service_destroyed", "Resolver service destroyed")
        main.removeCallbacksAndMessages(null)
        current?.set(true)
        executor.shutdownNow()
        server?.stop()
        megaServer?.stop()
        super.onDestroy()
    }

    companion object {
        const val TAG = "KinoExternalResolver"
        const val DIAG_PREFS = "resolver_diagnostics"
    }
}
