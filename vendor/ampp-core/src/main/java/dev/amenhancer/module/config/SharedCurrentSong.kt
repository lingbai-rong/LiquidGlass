package dev.amenhancer.module.config

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import dev.amenhancer.module.CurrentSongDetails
import java.util.concurrent.Executors

/** Small, transient metadata bridge for the module editor's Get ID action. */
internal object SharedCurrentSong {
    private data class Pending(val context: Context, val song: CurrentSongDetails?)
    private val lock = Any()
    private var pending: Pending? = null
    private var running = false
    private var lastSong: CurrentSongDetails? = null
    private var lastQueuedAt = -1L
    private val worker by lazy { Executors.newSingleThreadExecutor { job ->
        Thread(job, "ampp-current-song").apply { isDaemon = true }
    } }

    // Player callbacks only enqueue. Provider IPC never runs in the metadata/frame callback.
    fun publishAsync(context: Context, song: CurrentSongDetails?) {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (lastQueuedAt >= 0L && song == lastSong && now - lastQueuedAt < 5_000L) return
            lastSong = song; lastQueuedAt = now
            pending = Pending(context.applicationContext, song)
            if (running) return
            running = true
        }
        worker.execute {
            while (true) {
                val next = synchronized(lock) {
                    pending.also { pending = null; if (it == null) running = false }
                } ?: return@execute
                runCatching {
                    next.context.contentResolver.call(AmppSettingsProvider.BASE_URI,
                        "publish_song", null, encode(next.song))
                }
            }
        }
    }

    fun read(context: Context): CurrentSongDetails? = runCatching {
        decode(context.contentResolver.call(AmppSettingsProvider.BASE_URI, "current_song", null, null))
    }.getOrNull()

    fun encode(song: CurrentSongDetails?): Bundle = Bundle().apply {
        putLong("id", song?.appleMusicId ?: 0L)
        putString("title", song?.title?.take(512))
        putString("artist", song?.artist?.take(512))
    }

    fun decode(bundle: Bundle?): CurrentSongDetails? {
        val id = bundle?.getLong("id")?.takeIf { it > 0L } ?: return null
        return CurrentSongDetails(id, bundle.getString("title")?.take(512),
            bundle.getString("artist")?.take(512))
    }
}
