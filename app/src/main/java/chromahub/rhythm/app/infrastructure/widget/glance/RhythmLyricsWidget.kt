/*
 * Copyright (C) 2025 nift4 (Gramophone)
 * Modified for Rhythm by Anjishnu Nandi (cromaguy)
 *
 * SPDX-FileCopyrightText: 2025 nift4 <https://github.com/FoedusProgramme/Gramophone>
 * SPDX-FileCopyrightText: 2025-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.widget.glance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity

internal object RhythmLyricsWidget {
    private const val EXPANDED_HEIGHT_DP = 120

    data class Lines(
        val previous: String? = null,
        val current: String? = null,
        val next: String? = null,
        val hasLyrics: Boolean = false
    )

    private data class Playback(
        val id: String,
        val title: String,
        val artist: String
    ) {
        val hasSong: Boolean get() = id.isNotEmpty()
    }

    private data class TextFrame(val previous: String, val current: String, val next: String)
    private data class WidgetState(val height: Int, val playback: Playback, val text: TextFrame)

    private var playback: Playback? = null
    private var lines = Lines()
    private val rendered = mutableMapOf<Int, WidgetState>()
    private val handler = Handler(Looper.getMainLooper())
    private var updatePending = false

    @Synchronized
    fun updatePlayback(context: Context, force: Boolean = false) {
        val next = readPlayback(context)
        val before = playback
        if (next.id != before?.id) lines = Lines()
        playback = next
        if (force) {
            val manager = AppWidgetManager.getInstance(context)
            renderFull(context, manager, ids(context, manager), next)
        } else if (next != before) {
            scheduleUpdate(context)
        }
    }

    @Synchronized
    fun updateLyrics(context: Context, newLines: Lines) {
        val hadPlayback = playback != null
        val state = playback ?: readPlayback(context).also { playback = it }
        val next = if (state.hasSong) newLines else Lines()
        if (hadPlayback && next == lines) return

        lines = next
        scheduleUpdate(context)
    }

    @Synchronized
    fun render(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val state = readPlayback(context)
        if (state.id != playback?.id) lines = Lines()
        playback = state
        renderFull(context, manager, widgetIds, state)
    }

    @Synchronized
    fun forget(widgetIds: IntArray) {
        widgetIds.forEach { rendered.remove(it) }
    }

    // A seek or track transition can send several changes in the same frame.
    // Coalesce them so the launcher only receives the latest visible text.
    private fun scheduleUpdate(context: Context) {
        if (updatePending) return
        updatePending = true
        val appContext = context.applicationContext
        handler.postDelayed({ publishPending(appContext) }, 40L)
    }

    @Synchronized
    private fun publishPending(context: Context) {
        updatePending = false
        val state = playback ?: return
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids(context, manager)) {
            val old = rendered[id]
            if (old == null) {
                renderFull(context, manager, intArrayOf(id), state)
                continue
            }
            val newText = frame(context, state, old.height)
            if (old.playback == state && old.text == newText) continue

            val update = newViews(context)
            update.writePlayback(context, state, old.playback)
            update.writeLyrics(newText, old.text)
            manager.partiallyUpdateAppWidget(intArrayOf(id), update)
            rendered[id] = WidgetState(old.height, state, newText)
        }
    }

    private fun readPlayback(context: Context): Playback {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        return Playback(
            id = prefs.getString(RhythmMusicWidget.KEY_SONG_ID, "").orEmpty(),
            title = prefs.getString(RhythmMusicWidget.KEY_SONG_TITLE, "").orEmpty(),
            artist = prefs.getString(RhythmMusicWidget.KEY_ARTIST_NAME, "").orEmpty()
        )
    }

    private fun ids(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, RhythmLyricsWidgetReceiver::class.java))

    private fun newViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_lyrics)

    private fun renderFull(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        state: Playback
    ) {
        for (id in widgetIds) {
            val height = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
            val view = newViews(context)
            val text = frame(context, state, height)
            val expanded = height >= EXPANDED_HEIGHT_DP
            view.setViewVisibility(R.id.lyrics_widget_title, visibility(height >= 92))
            view.setViewVisibility(R.id.lyrics_widget_artist, visibility(expanded))
            view.setViewVisibility(R.id.lyrics_widget_previous_line, visibility(expanded))
            view.setViewVisibility(R.id.lyrics_widget_next_line, visibility(expanded))
            view.writePlayback(context, state)
            view.writeLyrics(text)
            view.setOnClickPendingIntent(
                R.id.lyrics_widget_content,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )

            manager.updateAppWidget(intArrayOf(id), view)
            rendered[id] = WidgetState(height, state, text)
        }
    }

    private fun frame(context: Context, state: Playback, height: Int): TextFrame {
        val current = when {
            !state.hasSong -> context.getString(R.string.rhythmlyricswidget_open_to_play)
            !lines.hasLyrics -> context.getString(R.string.rhythmlyricswidget_no_lyrics_available)
            else -> lines.current ?: "♪ " + context.getString(R.string.lyrics_instrumental)
        }
        val showNeighbors = height >= EXPANDED_HEIGHT_DP && state.hasSong
        return TextFrame(
            previous = if (showNeighbors) lines.previous.orEmpty() else "",
            current = current,
            next = if (showNeighbors) lines.next.orEmpty() else ""
        )
    }

    private fun RemoteViews.writeLyrics(text: TextFrame, before: TextFrame? = null) {
        if (before?.current != text.current) setTextViewText(R.id.lyrics_widget_current_line, text.current)
        if (before?.previous != text.previous) setTextViewText(R.id.lyrics_widget_previous_line, text.previous)
        if (before?.next != text.next) setTextViewText(R.id.lyrics_widget_next_line, text.next)
    }

    private fun RemoteViews.writePlayback(
        context: Context,
        state: Playback,
        before: Playback? = null
    ) {
        if (before?.title != state.title || before?.hasSong != state.hasSong) {
            val title = if (state.hasSong) state.title else context.getString(R.string.app_name)
            setTextViewText(R.id.lyrics_widget_title, title)
        }
        if (before?.artist != state.artist) setTextViewText(R.id.lyrics_widget_artist, state.artist)
    }

    private fun visibility(show: Boolean): Int = if (show) View.VISIBLE else View.GONE
}
