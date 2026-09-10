package com.rustik.miniautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.max

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var enabledByUser = false
        @Volatile var service: ClickAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var telegramForeground = false
    private var lastFrame: Bitmap? = null

    // Защита от повторного прыжка на одном и том же препятствии.
    private var lastObstacleX = -1000
    private var lastJumpAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        service = this
        monitoring = true
        monitor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        telegramForeground = pkg == "org.telegram.messenger"
        if (!telegramForeground) {
            lastObstacleX = -1000
        }
    }

    private fun monitor() {
        if (!monitoring) return

        if (enabledByUser &&
            telegramForeground &&
            android.os.Build.VERSION.SDK_INT >= 30
        ) {
            takeScreenshot()
        }

        // Чем меньше интервал, тем лучше ловим быстрое движение.
        handler.postDelayed({ monitor() }, 120L)
    }

    private fun takeScreenshot() {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (bmp == null) return

                    val current = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()

                    val old = lastFrame
                    lastFrame = current
                    old?.recycle()

                    val obstacle = findObstacle(current)
                    if (obstacle != null) {
                        val now = System.currentTimeMillis()

                        // Срабатываем, когда объект находится в правой части
                        // перед персонажем. Это автоматически масштабируется.
                        val dangerX = current.width * 0.66f

                        if (obstacle.centerX < dangerX &&
                            obstacle.centerX > current.width * 0.20f &&
                            now - lastJumpAt > 220L
                        ) {
                            // Не повторяем один и тот же объект бесконечно.
                            if (abs(obstacle.centerX - lastObstacleX) > current.width * 0.05f ||
                                now - lastJumpAt > 650L
                            ) {
                                lastObstacleX = obstacle.centerX.toInt()
                                lastJumpAt = now
                                testJump()
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // Android не дал снимок экрана — ждём следующий цикл.
                }
            }
        )
    }

    private data class Obstacle(
        val centerX: Float,
        val centerY: Float,
        val area: Int
    )

    /**
     * Ищет компактные серые/тёмные области в средней-нижней части игры.
     * В этой игре фон почти белый, поэтому препятствия заметно темнее фона.
     * Верхняя часть интерфейса и левая зона персонажа исключаются.
     */
    private fun findObstacle(bitmap: Bitmap): Obstacle? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 200 || h < 300) return null

        val x0 = (w * 0.18f).toInt()
        val x1 = (w * 0.94f).toInt()
        val y0 = (h * 0.28f).toInt()
        val y1 = (h * 0.78f).toInt()

        val step = max(4, minOf(w, h) / 180)
        var best: Obstacle? = null

        // Вместо тяжёлого CV ищем плотные группы небелых пикселей.
        // Несколько групп объединяем простым окном.
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val score = localDarkness(bitmap, x, y, step * 2)
                if (score >= 0.16f) {
                    val area = estimateBlobArea(bitmap, x, y, step * 3)
                    if (area in 20..18000) {
                        val candidate = Obstacle(
                            centerX = x.toFloat(),
                            centerY = y.toFloat(),
                            area = area
                        )

                        // Предпочитаем объект ближе к правой стороне.
                        if (best == null ||
                            candidate.centerX > best!!.centerX
                        ) {
                            best = candidate
                        }
                    }
                }
                x += step * 3
            }
            y += step * 3
        }

        return best
    }

    private fun gray(pixel: Int): Int {
        val r = pixel shr 16 and 255
        val g = pixel shr 8 and 255
        val b = pixel and 255
        return (r * 30 + g * 59 + b * 11) / 100
    }

    private fun localDarkness(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Float {
        val left = max(0, cx - radius)
        val right = minOf(bitmap.width - 1, cx + radius)
        val top = max(0, cy - radius)
        val bottom = minOf(bitmap.height - 1, cy + radius)

        var dark = 0
        var total = 0

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                // Препятствия на видео очень светло-серые, поэтому
                // используем порог выше обычного чёрного.
                if (gray(bitmap.getPixel(x, y)) < 232) dark++
                total++
                x += 2
            }
            y += 2
        }

        return if (total == 0) 0f else dark.toFloat() / total
    }

    private fun estimateBlobArea(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Int {
        val left = max(0, cx - radius)
        val right = minOf(bitmap.width - 1, cx + radius)
        val top = max(0, cy - radius)
        val bottom = minOf(bitmap.height - 1, cy + radius)

        var count = 0
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                if (gray(bitmap.getPixel(x, y)) < 232) count++
                x += 2
            }
            y += 2
        }
        return count
    }

    /**
     * Прыжок — короткое нажатие в центре игровой области.
     * Координаты вычисляются от фактического размера экрана.
     */
    fun testJump() {
        if (!telegramForeground && !isTestingFromApp()) return

        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.50f
        val y = dm.heightPixels * 0.68f

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    18L
                )
            )
            .build()

        dispatchGesture(gesture, null, null)
    }

    private fun isTestingFromApp(): Boolean = true

    override fun onInterrupt() {
        monitoring = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        monitoring = false
        handler.removeCallbacksAndMessages(null)
        lastFrame?.recycle()
        lastFrame = null
        service = null
        super.onDestroy()
    }
}
