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

    private fun diagnostic(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "$time $message"
        Log.i("KinoExternalResolver", line)
        synchronized(this) {
            val prefs = getSharedPreferences("resolver_diagnostics", MODE_PRIVATE)
            val previous = prefs.getString("lines", "") ?: ""
            val lines = (previous.lines().filter { it.isNotBlank() } + line).takeLast(250)
            prefs.edit().putString("lines", lines.joinToString("\\n")).apply()
        }
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val lastRead = maxOf(lastResolveAt, server?.lastActivityMs ?: 0L, megaServer?.lastActivityMs ?: 0L)
            if (!resolving && System.currentTimeMillis() - lastRead > 10 * 60 * 1000L) {
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
            diagnostic("resolve_received protocol=$protocol refLength=${ref.length} ref=${ref.take(180)}")
            current?.set(true)
            val cancelled = AtomicBoolean(false)
            current = cancelled
            resolving = true
            lastResolveAt = System.currentTimeMillis()
            executor.execute {
                var finished = false
                try {
                    diagnostic("resolve_start protocol=$protocol")
                    promoteToForeground()
                    val local: String
                    when (protocol) {
                        "abyss" -> {
                            callback.onProgress(-1, "Preparando Abyss… / Preparing Abyss…")
                            val parsed = AbyssSourceResolver.resolve(ref, cancelled) { p, msg ->
                                diagnostic("abyss progress=$p $msg")
                                callback.onProgress(p, msg)
                            }
                            if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
                            val mediaServer = synchronized(this@ResolverService) {
                                server ?: AbyssMediaServer(this@ResolverService).also { server = it }
                            }
                            local = mediaServer.register(parsed)
                        }
                        "mega" -> {
                            callback.onProgress(-1, "Preparando MEGA… / Preparing MEGA…")
                            val parsed = MegaSourceResolver.resolve(ref, cancelled) { p, msg ->
                                diagnostic("mega progress=$p $msg")
                                callback.onProgress(p, msg)
                            }
                            if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
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
                        .put("expiresInSeconds", 240)
                        .toString()
                    diagnostic("resolve_success protocol=$protocol local=$local")
                    callback.onResult(answer)
                    finished = true
                } catch (e: ResolveFailure) {
                    diagnostic("resolve_error protocol=$protocol code=${e.code} message=${e.message}")
                    runCatching { callback.onError(e.code, e.message ?: "No se pudo resolver la fuente / Could not resolve source") }
                    finished = true
                } catch (e: Throwable) {
                    Log.e("AbyssResolver", "external resolve failed for protocol=$protocol", e)
                    diagnostic("resolve_exception protocol=$protocol type=${e.javaClass.simpleName} message=${e.message}")
                    runCatching { callback.onError("unavailable", "El proveedor no respondió o no se pudo descifrar el video / Provider did not respond or video could not be decrypted") }
                    finished = true
                } finally {
                    if (!finished) runCatching { callback.onError("other", "Error inesperado / Unexpected error") }
                    resolving = false
                    if (current === cancelled) current = null
                    main.removeCallbacks(idleCheck)
                    main.postDelayed(idleCheck, 60_000L)
                }
            }
        }
        override fun cancel() {
            diagnostic("resolve_cancel requested")
            current?.set(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder.asBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 60_000L)
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(Intent(this, ResolverService::class.java))
            else startService(Intent(this, ResolverService::class.java))
        }
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
        main.removeCallbacksAndMessages(null)
        current?.set(true)
        executor.shutdownNow()
        server?.stop()
        megaServer?.stop()
        super.onDestroy()
    }
}
