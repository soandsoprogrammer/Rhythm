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
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import kotlin.math.roundToInt

internal object RhythmLyricsWidget {
    data class Lines(
        val previous: String? = null,
        val current: String? = null,
        val next: String? = null,
        val hasLyrics: Boolean = false
    )

    private data class Playback(
        val id: String,
        val title: String,
        val artist: String,
        val isPlaying: Boolean
    ) {
        val hasSong: Boolean get() = id.isNotEmpty()
    }

    private var playback: Playback? = null
    private var lines = Lines()

    // A partial update needs a complete layout first. Keep the size of each layout
    // we have rendered so line changes do not have to query widget options again.
    private val renderedHeights = mutableMapOf<Int, Int>()

    @Synchronized
    fun updatePlayback(context: Context, force: Boolean = false) {
        val newPlayback = readPlayback(context)
        if (newPlayback.id != playback?.id) lines = Lines()
        if (force || newPlayback != playback) {
            playback = newPlayback
            val manager = AppWidgetManager.getInstance(context)
            renderFull(context, manager, ids(context, manager), newPlayback)
        }
    }

    @Synchronized
    fun updateLyrics(context: Context, newLines: Lines) {
        val previousPlayback = playback
        val newPlayback = previousPlayback ?: readPlayback(context)
        val displayLines = if (newPlayback.hasSong) newLines else Lines()
        if (displayLines == lines && previousPlayback != null) return
        playback = newPlayback
        lines = displayLines

        val manager = AppWidgetManager.getInstance(context)
        for (id in ids(context, manager)) {
            val height = renderedHeights[id]
            if (height == null) {
                renderFull(context, manager, intArrayOf(id), newPlayback)
            } else {
                val update = newViews(context)
                update.setLyrics(context, newPlayback.hasSong, height)
                manager.partiallyUpdateAppWidget(intArrayOf(id), update)
            }
        }
    }

    @Synchronized
    fun render(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val newPlayback = readPlayback(context)
        if (newPlayback.id != playback?.id) lines = Lines()
        playback = newPlayback
        renderFull(context, manager, widgetIds, newPlayback)
    }

    @Synchronized
    fun forget(widgetIds: IntArray) {
        widgetIds.forEach { renderedHeights.remove(it) }
    }

    private fun readPlayback(context: Context): Playback {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        return Playback(
            id = prefs.getString(RhythmMusicWidget.KEY_SONG_ID, "").orEmpty(),
            title = prefs.getString(RhythmMusicWidget.KEY_SONG_TITLE, "").orEmpty(),
            artist = prefs.getString(RhythmMusicWidget.KEY_ARTIST_NAME, "").orEmpty(),
            isPlaying = prefs.getBoolean(RhythmMusicWidget.KEY_IS_PLAYING, false)
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
            val padding = ((if (height < 120) 6 else 10) * context.resources.displayMetrics.density).roundToInt()
            view.setViewPadding(R.id.lyrics_widget_content, padding, padding, padding, padding)
            view.setTextViewText(R.id.lyrics_widget_title, if (state.hasSong) state.title else context.getString(R.string.app_name))
            view.setViewVisibility(R.id.lyrics_widget_title, visibility(height >= 92))
            view.setTextViewText(R.id.lyrics_widget_artist, state.artist)
            view.setViewVisibility(R.id.lyrics_widget_artist, visibility(height >= 120 && state.hasSong && state.artist.isNotBlank()))
            view.setViewVisibility(R.id.lyrics_widget_controls, visibility(height >= 68 && state.hasSong))
            view.setImageViewResource(R.id.lyrics_widget_play_icon, if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            view.setContentDescription(
                R.id.lyrics_widget_play_pause,
                context.getString(if (state.isPlaying) R.string.action_pause else R.string.action_play)
            )
            view.setLyrics(context, state.hasSong, height)
            view.setColors(context)
            view.setOnClickPendingIntent(
                R.id.lyrics_widget_content,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            view.setOnClickPendingIntent(R.id.lyrics_widget_play_pause, playbackAction(context, MediaPlaybackService.ACTION_PLAY_PAUSE))
            view.setOnClickPendingIntent(R.id.lyrics_widget_previous, playbackAction(context, MediaPlaybackService.ACTION_SKIP_PREVIOUS))
            view.setOnClickPendingIntent(R.id.lyrics_widget_next, playbackAction(context, MediaPlaybackService.ACTION_SKIP_NEXT))

            manager.updateAppWidget(intArrayOf(id), view)
            renderedHeights[id] = height
        }
    }

    private fun RemoteViews.setLyrics(context: Context, hasSong: Boolean, height: Int) {
        val current = when {
            !hasSong -> context.getString(R.string.rhythmlyricswidget_open_to_play)
            !lines.hasLyrics -> context.getString(R.string.rhythmlyricswidget_no_lyrics_available)
            else -> lines.current ?: "♪ " + context.getString(R.string.lyrics_instrumental)
        }
        setTextViewText(R.id.lyrics_widget_current_line, current)
        setTextViewText(R.id.lyrics_widget_previous_line, lines.previous.orEmpty())
        setTextViewText(R.id.lyrics_widget_next_line, lines.next.orEmpty())
        setViewVisibility(R.id.lyrics_widget_previous_line, visibility(height >= 150 && hasSong && lines.previous != null))
        setViewVisibility(R.id.lyrics_widget_next_line, visibility(height >= 150 && hasSong && lines.next != null))
    }

    private fun RemoteViews.setColors(context: Context) {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (night) darkColorScheme() else lightColorScheme()
        }
        setInt(R.id.lyrics_widget_background, "setColorFilter", colors.surfaceContainer.toArgb())
        setTextColor(R.id.lyrics_widget_title, colors.onSurface.toArgb())
        setTextColor(R.id.lyrics_widget_artist, colors.onSurfaceVariant.toArgb())
        setTextColor(R.id.lyrics_widget_current_line, colors.primary.toArgb())
        setTextColor(R.id.lyrics_widget_previous_line, colors.onSurfaceVariant.toArgb())
        setTextColor(R.id.lyrics_widget_next_line, colors.onSurfaceVariant.toArgb())
        setInt(R.id.lyrics_widget_previous_background, "setColorFilter", colors.tertiary.toArgb())
        setInt(R.id.lyrics_widget_next_background, "setColorFilter", colors.tertiary.toArgb())
        setInt(R.id.lyrics_widget_previous_icon, "setColorFilter", colors.onTertiary.toArgb())
        setInt(R.id.lyrics_widget_next_icon, "setColorFilter", colors.onTertiary.toArgb())
        setInt(R.id.lyrics_widget_play_background, "setColorFilter", colors.primary.toArgb())
        setInt(R.id.lyrics_widget_play_icon, "setColorFilter", colors.onPrimary.toArgb())
    }

    private fun playbackAction(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            0,
            Intent(context, MediaPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun visibility(show: Boolean): Int = if (show) View.VISIBLE else View.GONE
}
