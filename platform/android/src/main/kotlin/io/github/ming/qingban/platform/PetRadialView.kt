package io.github.ming.qingban.platform

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.*
import io.github.ming.qingban.domain.CompanionMenuLayout

/** Bounded overlay menu, independent of service lifetime and window positioning. */
internal class PetRadialView(context: Context,onAction: (String)->Unit): FrameLayout(context) {
    private val density=resources.displayMetrics.density
    private val actions=listOf("对话" to "assistant","事项" to "today","备忘" to "memo","日历" to "calendar","闹钟" to "alarm","专注" to "focus","动态" to "motion")
    init {
        val button=(CompanionMenuLayout.BUTTON*density).toInt()
        actions.forEach { (label,route) ->
            addView(TextView(context).apply {
                text=label; textSize=12f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); gravity=Gravity.CENTER; setTextColor(Color.rgb(47,91,73)); contentDescription="桌宠$label"
                val fill=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(if(route=="motion") Color.rgb(237,232,247) else Color.rgb(221,243,231)) }
                background=RippleDrawable(ColorStateList.valueOf(Color.argb(45,35,123,97)),fill,null)
                elevation=2*density
                setOnClickListener { onAction(route) }
            },LayoutParams(button,button))
        }
        arrange(true)
    }
    fun arrange(outwardRight: Boolean) {
        CompanionMenuLayout.points(actions.size,outwardRight).forEachIndexed { index,point ->
            val child=getChildAt(index)
            child.layoutParams=(child.layoutParams as LayoutParams).apply { leftMargin=((point.x-CompanionMenuLayout.BUTTON/2)*density).roundToInt(); topMargin=((point.y-CompanionMenuLayout.BUTTON/2)*density).roundToInt() }
        }
    }
}
