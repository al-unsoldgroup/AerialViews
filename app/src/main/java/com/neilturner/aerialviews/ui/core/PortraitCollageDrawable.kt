package com.neilturner.aerialviews.ui.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Draw two already decoded photos as one slide without creating a full-screen composite bitmap. */
internal class PortraitCollageDrawable(
    private val first: Drawable,
    private val second: Drawable,
    private val firstSize: PortraitCollageLayout.Size,
    private val secondSize: PortraitCollageLayout.Size,
) : Drawable() {
    private val background = Paint().apply { color = Color.BLACK }

    override fun draw(canvas: Canvas) {
        val layout =
            PortraitCollageLayout.arrange(PortraitCollageLayout.Size(bounds.width(), bounds.height()), firstSize, secondSize) ?: return
        canvas.drawRect(bounds, background)
        drawPhoto(canvas, first, layout.first)
        drawPhoto(canvas, second, layout.second)
    }

    private fun drawPhoto(
        canvas: Canvas,
        photo: Drawable,
        box: PortraitCollageLayout.Box,
    ) {
        photo.setBounds(bounds.left + box.left, bounds.top + box.top, bounds.left + box.right, bounds.top + box.bottom)
        photo.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        background.alpha = alpha
        first.alpha = alpha
        second.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        background.colorFilter = colorFilter
        first.colorFilter = colorFilter
        second.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
