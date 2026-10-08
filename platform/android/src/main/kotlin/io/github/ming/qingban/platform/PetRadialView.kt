package io.github.ming.qingban.platform

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.*

/** Bounded overlay menu, independent of service lifetime and window positioning. */
internal class PetRadialView(context: Context,onAction: (String)->Unit): FrameLayout(context) {
    init {
        val density=resources.displayMetrics.density
        val size=(188*density).toInt(); val button=(40*density).toInt(); val radius=66*density
        background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.argb(232,238,247,242)); setStroke(density.toInt().coerceAtLeast(1),Color.rgb(181,210,196)) }
        listOf("对话" to "assistant","事项" to "today","备忘" to "memo","日历" to "calendar","闹钟" to "alarm","专注" to "focus","动态" to "motion","收起" to "close").forEachIndexed { i,(label,route) ->
            val angle=Math.toRadians(-90.0+i*45)
            addView(TextView(context).apply {
                text=label; textSize=12f; gravity=Gravity.CENTER; setTextColor(Color.rgb(47,91,73)); contentDescription="桌宠$label"
                background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(if(route=="motion") Color.rgb(229,220,243) else Color.rgb(218,238,228)) }
                setOnClickListener { onAction(route) }
            },LayoutParams(button,button).apply { leftMargin=(size/2+cos(angle)*radius-button/2).toInt(); topMargin=(size/2+sin(angle)*radius-button/2).toInt() })
        }
    }
}
