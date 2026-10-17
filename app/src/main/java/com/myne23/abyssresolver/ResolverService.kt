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

    private val binder = object : IExternalResolver.Stub() {
        override fun resolve(protocol: String, ref: String, callback: IExternalResolverCallback) {
            current?.set(true)
            val cancelled = AtomicBoolean(false)
            current = cancelled
            executor.execute {
                var finished = false
                try {
                    promoteToForeground()
                    if (protocol != "abyss") throw ResolveFailure("unsupported", "Protocolo no compatible / Unsupported protocol")
                    callback.onProgress(-1, "Preparando Abyss… / Preparing Abyss…")
                    val parsed = AbyssSourceResolver.resolve(ref, cancelled) { p, msg -> callback.onProgress(p, msg) }
                    if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
                    val mediaServer = synchronized(this@ResolverService) {
                        server ?: AbyssMediaServer(this@ResolverService).also { server = it }
                    }
                    val local = mediaServer.register(parsed)
                    val answer = JSONObject()
                        .put("url", local)
                        .put("mime", "video/mp4")
                        .put("expiresInSeconds", 240)
                        .toString()
                    callback.onResult(answer)
                    finished = true
                } catch (e: ResolveFailure) {
                    runCatching { callback.onError(e.code, e.message ?: "No se pudo resolver Abyss / Could not resolve Abyss") }
                    finished = true
                } catch (e: Throwable) {
                    Log.e("AbyssResolver", "resolve failed", e)
                    runCatching { callback.onError("unavailable", "Abyss no respondió o no se pudo descifrar el video / Abyss did not respond or the video could not be decrypted") }
                    finished = true
                } finally {
                    if (!finished) runCatching { callback.onError("other", "Error inesperado / Unexpected error") }
                }
            }
        }
        override fun cancel() { current?.set(true) }
    }

    override fun onBind(intent: Intent?): IBinder = binder.asBinder()
    override fun onDestroy() {
        current?.set(true)
        executor.shutdownNow()
        server?.stop()
        super.onDestroy()
    }
}
