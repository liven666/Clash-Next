package com.follow.clash

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

internal data class AppColors(
    val background: Int,
    val surface: Int,
    val surfaceSoft: Int,
    val primary: Int,
    val onPrimary: Int,
    val primarySoft: Int,
    val text: Int,
    val muted: Int,
    val outline: Int,
    val success: Int,
    val error: Int,
) {
    companion object {
        fun forNight(night: Boolean) = if (night) {
            AppColors(
                0xFF10151F.toInt(), 0xFF1A2231.toInt(), 0xFF242E40.toInt(),
                0xFFB0C3FF.toInt(), 0xFF142A60.toInt(), 0xFF293B64.toInt(),
                0xFFEEF2FB.toInt(), 0xFFA1AEC4.toInt(), 0xFF303B4F.toInt(),
                0xFF7CD9B4.toInt(), 0xFFFFADB4.toInt(),
            )
        } else {
            AppColors(
                0xFFF5F7FB.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0F3F9.toInt(),
                0xFF3F64E9.toInt(), 0xFFFFFFFF.toInt(), 0xFFEAF0FF.toInt(),
                0xFF19263F.toInt(), 0xFF718099.toInt(), 0xFFE4E9F2.toInt(),
                0xFF188663.toInt(), 0xFFBB4254.toInt(),
            )
        }
    }
}

internal enum class UiIcon {
    HOME, NODES, CONNECTIONS, SETTINGS, SHIELD, POWER, CHEVRON,
    UP, DOWN, CLOCK, SEARCH, PLUS, QR, FOLDER, FILE, REFRESH,
    LOG, EXPORT, TRASH, EXTERNAL, CHECK, INFO,
}

/** Small, code-drawn vector icons keep the native client independent of a UI runtime. */
internal class AppIconDrawable(
    private val icon: UiIcon,
    color: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0], points[1])
                var index = 2
                while (index < points.size) {
                    lineTo(points[index], points[index + 1])
                    index += 2
                }
            }
            canvas.drawPath(path, paint)
        }
        fun rect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 2f) {
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
        }
        when (icon) {
            UiIcon.HOME -> {
                line(3f, 10f, 12f, 3f, 21f, 10f)
                line(5f, 9f, 5f, 21f, 10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f, 19f, 21f, 19f, 9f)
            }
            UiIcon.NODES -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawOval(RectF(8f, 3f, 16f, 21f), paint)
                line(3f, 12f, 21f, 12f)
                canvas.drawArc(RectF(4f, 5f, 20f, 13f), 10f, 160f, false, paint)
            }
            UiIcon.CONNECTIONS -> {
                rect(3f, 6f, 13f, 13f, 3.5f)
                rect(11f, 11f, 21f, 18f, 3.5f)
                line(8f, 12f, 16f, 12f)
            }
            UiIcon.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 6f, paint)
                canvas.drawCircle(12f, 12f, 2.2f, paint)
                repeat(8) {
                    val angle = it * Math.PI / 4
                    line(
                        12f + cos(angle).toFloat() * 6f,
                        12f + sin(angle).toFloat() * 6f,
                        12f + cos(angle).toFloat() * 9f,
                        12f + sin(angle).toFloat() * 9f,
                    )
                }
            }
            UiIcon.SHIELD -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 2.5f)
                    lineTo(20f, 6f)
                    lineTo(19f, 14f)
                    quadTo(17f, 19.5f, 12f, 22f)
                    quadTo(7f, 19.5f, 5f, 14f)
                    lineTo(4f, 6f)
                    close()
                }, paint)
                line(8.5f, 12f, 11f, 14.5f, 15.5f, 9.5f)
            }
            UiIcon.POWER -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -50f, 280f, false, paint)
                line(12f, 2f, 12f, 11f)
            }
            UiIcon.CHEVRON -> line(9f, 6f, 15f, 12f, 9f, 18f)
            UiIcon.UP -> {
                line(12f, 20f, 12f, 4f)
                line(6f, 10f, 12f, 4f, 18f, 10f)
            }
            UiIcon.DOWN -> {
                line(12f, 4f, 12f, 20f)
                line(6f, 14f, 12f, 20f, 18f, 14f)
            }
            UiIcon.CLOCK -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                line(12f, 7f, 12f, 12f, 16f, 14f)
            }
            UiIcon.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint)
                line(15.5f, 15.5f, 21f, 21f)
            }
            UiIcon.PLUS -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }
            UiIcon.QR -> {
                rect(3f, 3f, 9f, 9f, 1f)
                rect(15f, 3f, 21f, 9f, 1f)
                rect(3f, 15f, 9f, 21f, 1f)
                line(15f, 15f, 18f, 15f, 18f, 18f, 21f, 18f, 21f, 21f, 15f, 21f)
                line(6f, 6f, 6.1f, 6f)
                line(18f, 6f, 18.1f, 6f)
                line(6f, 18f, 6.1f, 18f)
            }
            UiIcon.FOLDER -> {
                line(3f, 8f, 3f, 5f, 10f, 5f, 12f, 8f, 21f, 8f, 21f, 20f, 3f, 20f, 3f, 8f, 21f, 8f)
            }
            UiIcon.FILE, UiIcon.LOG -> {
                line(14f, 3f, 5f, 3f, 5f, 21f, 19f, 21f, 19f, 8f, 14f, 3f, 14f, 8f, 19f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 14f, 16f)
            }
            UiIcon.REFRESH -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -80f, 290f, false, paint)
                line(17f, 3f, 20f, 7f, 15f, 8f)
            }
            UiIcon.EXPORT -> {
                line(12f, 15f, 12f, 3f)
                line(7f, 8f, 12f, 3f, 17f, 8f)
                line(4f, 14f, 4f, 21f, 20f, 21f, 20f, 14f)
            }
            UiIcon.TRASH -> {
                line(4f, 6f, 20f, 6f)
                line(9f, 6f, 9f, 3f, 15f, 3f, 15f, 6f)
                line(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f)
                line(10f, 10f, 10f, 17f)
                line(14f, 10f, 14f, 17f)
            }
            UiIcon.EXTERNAL -> {
                line(13f, 3f, 21f, 3f, 21f, 11f)
                line(21f, 3f, 11f, 13f)
                line(9f, 5f, 4f, 5f, 4f, 21f, 19f, 21f, 19f, 16f)
            }
            UiIcon.CHECK -> line(5f, 12f, 10f, 17f, 20f, 7f)
            UiIcon.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                line(12f, 11f, 12f, 17f)
                line(12f, 7f, 12.1f, 7f)
            }
        }
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
