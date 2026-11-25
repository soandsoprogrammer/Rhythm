/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.local.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R

@Composable
internal fun ShenyeMasthead(serverLibrary: Boolean, modifier: Modifier = Modifier) {
    val ink = Color(0xFF1B1C20)
    val paper = Color(0xFFF7ECDD)
    val blue = Color(0xFF2545C7)
    val orange = Color(0xFFF15B37)

    Row(
        modifier = modifier.fillMaxWidth().background(ink).padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.61f).padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.shenye_name),
                color = paper,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black
            )
            Text(
                stringResource(if (serverLibrary) R.string.shenye_server_library else R.string.shenye_local_library),
                color = paper,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Box(modifier = Modifier.weight(0.39f)) {
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                val unit = size.minDimension
                val center = Offset(size.width * 0.56f, size.height * 0.52f)
                drawRect(blue, Offset(unit * 0.08f, unit * 0.17f), Size(unit * 0.49f, unit * 0.67f))
                drawCircle(orange, unit * 0.36f, center)
                drawCircle(ink, unit * 0.28f, center, style = Stroke(unit * 0.025f))
                drawCircle(ink, unit * 0.20f, center, style = Stroke(unit * 0.015f))
                drawCircle(paper, unit * 0.065f, center)
                drawLine(paper, Offset(size.width * 0.24f, size.height * 0.86f),
                    Offset(size.width * 0.89f, size.height * 0.16f), unit * 0.023f)
            }
        }
    }
}
