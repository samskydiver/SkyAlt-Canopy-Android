package com.skyalt.canopy.tools.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * An eraser on a line, for the planning tools' Reset Plan buttons (iOS uses
 * the SF Symbol "eraser"; the Material icon set in use has none).
 */
val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Eraser", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            // The eraser, tilted 45°.
            moveTo(13.5f, 3.5f)
            lineTo(20.5f, 10.5f)
            lineTo(12f, 19f)
            lineTo(8f, 19f)
            lineTo(3.5f, 14.5f)
            close()
            // Where the rubber meets the sleeve.
            moveTo(8.5f, 9f)
            lineTo(15.5f, 15.5f)
            // The line it rubs out.
            moveTo(12f, 21f)
            lineTo(20.5f, 21f)
        }
        .build()
}
