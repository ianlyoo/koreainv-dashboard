package com.koreainv.dashboard.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, consistent outline symbols keep navigation quieter than the financial values. */
object DashboardIcons {
    val Portfolio = outline("Portfolio") {
        moveTo(4f, 20f); lineTo(4f, 13f)
        moveTo(9.3f, 20f); lineTo(9.3f, 9f)
        moveTo(14.7f, 20f); lineTo(14.7f, 12f)
        moveTo(20f, 20f); lineTo(20f, 4f)
        moveTo(3f, 8f); lineTo(9f, 4f); lineTo(14f, 7f); lineTo(20f, 2f)
    }
    val Assets = outline("Assets") {
        moveTo(19f, 7f); lineTo(5f, 7f)
        curveTo(2f, 7f, 2f, 3f, 5f, 3f)
        lineTo(18f, 3f); lineTo(18f, 7f)
        moveTo(3f, 5f); lineTo(3f, 18f)
        curveTo(3f, 20f, 4f, 21f, 6f, 21f)
        lineTo(20f, 21f); lineTo(20f, 7f); lineTo(19f, 7f)
        moveTo(20f, 12f); lineTo(15f, 12f)
        curveTo(12f, 12f, 12f, 17f, 15f, 17f)
        lineTo(20f, 17f)
        moveTo(16f, 14.5f); lineTo(16.1f, 14.5f)
    }
    val Trades = outline("Trades") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(16f, 3f); lineTo(20f, 7f); lineTo(16f, 11f)
        moveTo(20f, 17f); lineTo(4f, 17f)
        moveTo(8f, 13f); lineTo(4f, 17f); lineTo(8f, 21f)
    }

    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero, pathBuilder = block)
        }.build()
}
