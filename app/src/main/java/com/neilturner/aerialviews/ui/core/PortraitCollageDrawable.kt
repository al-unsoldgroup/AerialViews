package com.neilturner.aerialviews.ui.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Draw already decoded photos as one slide without creating a full-screen composite bitmap. */
internal class PortraitCollageDrawable(
    private val first: Drawable,
    private val second: Drawable,
    private val firstSize: PortraitCollageLayout.Size,
    private val secondSize: PortraitCollageLayout.Size,
    private val third: Drawable? = null,
    private val thirdSize: PortraitCollageLayout.Size? = null,
    private val firstFocus: FocusCrop.Point? = null,
    private val secondFocus: FocusCrop.Point? = null,
    private val thirdFocus: FocusCrop.Point? = null,
) : Drawable() {
    private val background = Paint().apply { color = Color.BLACK }

    override fun draw(canvas: Canvas) {
        val canvasSize = PortraitCollageLayout.Size(bounds.width(), bounds.height())
        if (third != null && thirdSize != null) {
            val layout = PortraitCollageLayout.arrangeThree(canvasSize, firstSize, secondSize, thirdSize) ?: return
            canvas.drawRect(bounds, background)
            drawPhoto(canvas, first, firstSize, layout.first, firstFocus)
            drawPhoto(canvas, second, secondSize, layout.second, secondFocus)
            drawPhoto(canvas, third, thirdSize, layout.third, thirdFocus)
            return
        }
        val layout =
            PortraitCollageLayout.arrange(canvasSize, firstSize, secondSize) ?: return
        canvas.drawRect(bounds, background)
        drawPhoto(canvas, first, firstSize, layout.first, firstFocus)
        drawPhoto(canvas, second, secondSize, layout.second, secondFocus)
    }

    private fun drawPhoto(
        canvas: Canvas,
        photo: Drawable,
        size: PortraitCollageLayout.Size,
        box: PortraitCollageLayout.Box,
        focus: FocusCrop.Point?,
    ) {
        val left = bounds.left + box.left
        val top = bounds.top + box.top
        val width = box.right - box.left
        val height = box.bottom - box.top
        val placement = FocusCrop.place(size.width, size.height, width, height, focus) ?: return
        val drawnWidth = kotlin.math.ceil(size.width * placement.scale.toDouble()).toInt()
        val drawnHeight = kotlin.math.ceil(size.height * placement.scale.toDouble()).toInt()
        val x = left + kotlin.math.floor(placement.x.toDouble()).toInt()
        val y = top + kotlin.math.floor(placement.y.toDouble()).toInt()
        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)
        photo.setBounds(x, y, x + drawnWidth, y + drawnHeight)
        photo.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        background.alpha = alpha
        first.alpha = alpha
        second.alpha = alpha
        third?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        background.colorFilter = colorFilter
        first.colorFilter = colorFilter
        second.colorFilter = colorFilter
        third?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
