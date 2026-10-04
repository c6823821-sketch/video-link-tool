package com.c6823821.videolinktool

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/**
 * VideoView that keeps the real aspect ratio of the clip and centres itself inside
 * the box it is given. 16:9, 9:16, 4:3, 3:4 and anything else all render without
 * stretching or being glued to one side.
 */
class AspectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : VideoView(context, attrs) {

    private var ratio = 0f

    fun applyVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val value = width.toFloat() / height.toFloat()
        if (value > 0f && value != ratio) {
            ratio = value
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (ratio <= 0f || maxWidth <= 0 || maxHeight <= 0) {
            setMeasuredDimension(maxWidth, maxHeight)
            return
        }
        var width = maxWidth
        var height = (width / ratio).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height * ratio).toInt()
        }
        setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}