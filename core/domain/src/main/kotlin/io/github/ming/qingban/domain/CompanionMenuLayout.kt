package io.github.ming.qingban.domain

import kotlin.math.cos
import kotlin.math.sin

/** Shared geometry for the overlay and in-app shortcuts; all dimensions are dp. */
object CompanionMenuLayout {
    const val WIDTH = 156
    const val HEIGHT = 264
    const val BUTTON = 48
    const val PORTRAIT_WIDTH = 46
    const val PORTRAIT_HEIGHT = 76
    const val PORTRAIT_TOP = 94
    fun portraitLeft(outwardRight: Boolean) = if(outwardRight) 5 else WIDTH-5-PORTRAIT_WIDTH
    data class Point(val x: Float, val y: Float)
    fun points(count: Int, outwardRight: Boolean): List<Point> {
        require(count in 2..7)
        return List(count) { index ->
            val angle=Math.toRadians(-90.0+180.0*index/(count-1))
            val x=(28+100*cos(angle)).toFloat()
            Point(if(outwardRight) x else WIDTH-x,(132+100*sin(angle)).toFloat())
        }
    }
}
