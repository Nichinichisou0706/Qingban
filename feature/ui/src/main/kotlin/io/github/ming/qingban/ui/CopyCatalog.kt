package io.github.ming.qingban.ui

import java.time.Clock
import java.time.LocalDate

// Presentation copy uses local context, without model requests or stored user content.
enum class CopyPeriod { MORNING, AFTERNOON, EVENING, NIGHT }
enum class AssistantMood { IDLE, WAITING, BUSY }
data class CopyTime(val date: LocalDate, val period: CopyPeriod) {
    val key get() = "$date:$period"
    companion object {
        fun now(clock: Clock): CopyTime {
            val now=clock.instant().atZone(clock.zone)
            val period=when(now.hour) { in 6..11 -> CopyPeriod.MORNING; in 12..17 -> CopyPeriod.AFTERNOON; in 18..22 -> CopyPeriod.EVENING; else -> CopyPeriod.NIGHT }
            return CopyTime(now.toLocalDate(),period)
        }
    }
}
data class DayProgress(val pending: Int, val completed: Int) {
    val state get() = if(pending>0) "pending" else if(completed>0) "done" else "empty"
}
data class CopyPool(val id: String, val choices: List<String>) {
    init { require(choices.isNotEmpty() && choices.all { it.isNotBlank() }) }
}

// Each position advances independently. Compose preserves its selected index while visible.
class CopyRotation(private val seed: Int, private val previous: MutableMap<String,Int> = mutableMapOf()) {
    fun next(slot: String, size: Int): Int {
        require(size>0)
        val index=previous[slot]?.let { (it+1)%size } ?: Math.floorMod(seed xor slot.hashCode(),size)
        previous[slot]=index
        return index
    }
    fun snapshot(): Map<String,Int> = previous.toMap()+("__seed" to seed)
    companion object {
        fun restore(saved: Map<String,Int>) = CopyRotation(saved.getValue("__seed"),saved.filterKeys { it!="__seed" }.toMutableMap())
    }
}

object CopyCatalog {
    private fun pool(id: String, vararg choices: String) = CopyPool(id,choices.toList())
    fun homeHeadline(time: CopyTime, progress: DayProgress): CopyPool = when(progress.state) {
        "pending" -> pool("home.pending","今天的事，\n一件件来","先做一件，\n慢慢就顺了","按你的步调，\n安排就好","把想做的，\n慢慢变成做过")
        "done" -> pool("home.done","安排做完，\n给自己留白","做过的事，\n都替你记着","忙完这一段，\n歇一会儿吧")
        else -> when(time.period) {
            CopyPeriod.MORNING -> pool("home.morning","早安铭哥，\n从小事开始","新的一天，\n慢慢安排吧","今天想做的，\n先记一件吧")
            CopyPeriod.AFTERNOON -> pool("home.afternoon","下午的事，\n慢慢理清楚","把小小想法，\n放进今天吧","把今天，\n轻轻安排好")
            CopyPeriod.EVENING -> pool("home.evening","收好今天，\n也记下明天","晚上的想法，\n先放在这里","今天与明天，\n慢慢安排好")
            CopyPeriod.NIGHT -> pool("home.night","夜深了，\n想法先记下","明天的事，\n先替你收好","小小的念头，\n留到明天吧")
        }
    }
    fun homeSupport(progress: DayProgress): CopyPool = when(progress.state) {
        "pending" -> pool("support.pending","铭哥，还有 ${progress.pending} 件，先挑一件来。","不必一口气做完，我陪你理清顺序。","想到新的事，随时告诉我。")
        "done" -> pool("support.done","铭哥，今天已安排的事情做完啦。","想继续安排，或留点空白，都可以。","完成记录留好了，接下来按你的节奏。")
        else -> pool("support.empty","铭哥，想到什么就告诉我。","先记下来，时间可以慢慢定。","工作、生活和灵感，都能放进来。","不确定怎么安排，我们一起商量。")
    }
    val homeCta=pool("home.cta","和 GPT 娘聊聊","告诉我一件事","一起安排一下")
    fun dashboardTitle(progress: DayProgress)=pool("dashboard.title.${progress.state}","铭哥的生活概览","铭哥，看看最近的安排","把日常慢慢理清楚")
    fun dashboardSupport(progress: DayProgress)=when(progress.state) {
        "pending" -> pool("dashboard.pending","还有 ${progress.pending} 件，按自己的节奏来。","${progress.pending} 件待办已经记好，先选一件。","不用赶在一起，今天还有 ${progress.pending} 件。")
        "done" -> pool("dashboard.done","今天安排的事情已完成。","今天的安排已做完，留点时间给自己。","完成记录都在，想继续安排也可以。")
        else -> pool("dashboard.empty","今天还没安排，留点空白也很好。","想做的事可以随时添进今天。","从一件小事开始，也是一种安排。")
    }
    val calendarTip=pool("calendar.tip","点日期看安排","选一天，看看安排","点开一天的记录")
    fun assistantGreeting(time: CopyTime)=when(time.period) {
        CopyPeriod.MORNING -> pool("assistant.morning","铭哥，早安呀","铭哥，新的一天啦","早安，我在这里")
        CopyPeriod.AFTERNOON -> pool("assistant.afternoon","铭哥，下午好","铭哥，我在这里","想记什么，告诉我吧")
        CopyPeriod.EVENING -> pool("assistant.evening","铭哥，晚上好","铭哥，今天过得怎样","晚上的安排，一起想吧")
        CopyPeriod.NIGHT -> pool("assistant.night","铭哥，夜深了呀","想法先交给我吧","铭哥，我在这里")
    }
    fun assistantSupport(mood: AssistantMood, hasHistory: Boolean)=when(mood) {
        AssistantMood.BUSY -> pool("assistant.busy","正在整理这次安排。","我在处理刚才的请求。","正在核对你的安排。")
        AssistantMood.WAITING -> pool("assistant.waiting","还有一点细节，想听你的选择。","不确定的地方，我们先商量。","补充刚才的问题，也能另记一件事。")
        AssistantMood.IDLE -> if(hasHistory) pool("assistant.continue","还有什么想记的，接着说吧。","需要调整，也可以继续告诉我。","一件件来，我在这里。") else pool("assistant.idle","随手记，也可以慢慢商量。","先说想做什么，细节慢慢补。","时间没想好，也能先记着。","工作和生活，都可以从一句话开始。")
    }
    fun assistantInput(mood: AssistantMood)=when(mood) {
        AssistantMood.WAITING -> pool("input.waiting","告诉我你的选择","补充刚才的安排细节","先回答刚才的问题吧")
        AssistantMood.BUSY -> pool("input.busy","可以先写下下一件事","整理中，先写下新想法","先记在这里，稍后再发送")
        AssistantMood.IDLE -> pool("input.idle","铭哥，想安排什么？","说一件想做的事吧","先记下来，时间慢慢定","工作、生活，都可以说")
    }
    val busy=pool("busy","正在整理你的想法…","正在核对这次安排…","正在处理刚才的请求…")
    val taskExamples=pool("examples.task","明天买牛奶","周五交报告","明天去取快递","后天整理书桌")
    val alarmExamples=pool("examples.alarm","明早叫我起床","明天提醒我取快递","明晚提醒我散步","明天叫我起床")
    val noteExamples=pool("examples.note","记下想看的电影","明晚整理照片","记一下周末想去的地方","今晚整理购物清单")
    val searchHint=pool("search.memo","找找记过的事情","输入名字，找一条记录","找找之前留下的想法")
    val alarmIntro=pool("alarm.intro","重要的事，按你的方式提醒。","音乐或振动，陪你准时出发。","起床、出门和小事，都能留个提醒。")
    val alarmEmpty=pool("empty.alarm","给重要的事留个提醒","喜欢的旋律，陪你准时出发","先设一个提醒，时间到了见")
    fun emptyTitle(page: Int, completed: Boolean, searching: Boolean, day: LocalDate, today: LocalDate): CopyPool {
        if(searching) return pool("empty.search","暂时没找到这条记录","这些关键词还没匹配到记录","换个说法，再找找吧")
        if(completed) return pool("empty.completed","完成的事情会留在这里","做完一件，就能在这里看到","这里替你留好完成记录")
        return when(page) {
            2 -> pool("empty.memo","灵感和小事，都可以先记着","还没定时间的想法，先放这里","想到一件事，就先留下来")
            3 -> alarmEmpty
            else -> when { day.isBefore(today) -> pool("empty.past","这天还没有事项记录","这一天的记录还是空白","想补记这天的事，也可以")
                day.isAfter(today) -> pool("empty.future","这天还没有安排","给未来的这天留点空白","想到这天要做的事，就记下来")
                else -> pool("empty.today","今天留点空白也很好","今天想做什么，从一件事开始","安排随时能加进来，不用着急") }
        }
    }
    fun emptyHint(searching: Boolean)=if(searching) pool("empty.searchHint","试试更短的关键词，或清空搜索看看。","可以换个名称，也能按正文里的词查找。","清空关键词，查看全部备忘。") else pool("empty.addHint","点这里添加，或说一句话交给 GPT 娘。","可以自己填，也可以和 GPT 娘一起安排。","先记一件事，具体时间以后再定。")
    fun libraryIntro(count: Int)=if(count>0) pool("library.hasSources","已有 $count 份资料，常用信息都能留在这里。","铭哥，$count 份参考资料已经留好，需要时可以查找。","笔记和安排集中放好，查资料时会更方便。") else pool("library.new","把课表、工作安排和笔记留在这里。","铭哥，先放一份常用资料，让安排更有依据。","计划、笔记和生活信息，都能成为长期参考。")
    val libraryEmpty=pool("empty.library","先认识你的日常","常用信息，留一份在这里","给以后的安排准备点参考")
    val libraryEmptyHint=pool("empty.libraryHint","可以放入课表、工作安排或项目说明。","粘贴笔记，或导入一份常用文件。","先从一份经常查的资料开始。")
    val libraryNameHint=pool("library.nameHint","起一个以后好找的名字","比如：工作安排或旅行计划","给这份参考资料起个名字")
    val libraryBodyHint=pool("library.bodyHint","粘贴计划、笔记或常用信息","放入希望长期参考的内容","例如课表、工作安排或资料摘录")
    val detailHint=pool("editor.detailHint","补充地点、材料或准备步骤","比如要带的东西和注意事项","想到什么，再补一句就好")
    fun memoryIntro(learning: Boolean)=if(learning) pool("memory.observing","慢慢了解你的习惯","从你选过的安排里发现规律","你的选择，会让我更懂你的节奏") else pool("memory.off","按你说的，记住偏好","想让我记住什么，告诉我吧","先听你说，再慢慢了解")
    val memoryEmpty=pool("empty.memory","我会先听你说","你喜欢怎样安排一天","让安排更贴近你的习惯")
    val memoryEmptyHint=pool("empty.memoryHint","可以添加运动、作息或工作的安排偏好。","比如：晚上适合运动，早上先处理工作。","把希望我记住的安排方式写下来。")
    val modelIntro=pool("model.intro","选择你习惯的模型，事项规则和记忆保持一致。未配置时可离线记录。","接入自己的模型，就能继续商量复杂安排；未配置时也能离线记事。","铭哥，可以在这里连接模型助手；没有配置也能使用离线记录。")
    val aboutTagline=pool("about.tagline","一句话安排生活，也给没定日期的想法留个位置。","把日常记下来，让想做的事有个去处。","备忘、日历与提醒，陪你按自己的节奏安排。")
    val ringTag=pool("ring.tag","铭哥，到点啦","铭哥，提醒时间到啦","安排好的时间到了")
    fun ringSupport(wakeup: Boolean)=if(wakeup) pool("ring.wakeup","慢慢醒来，新的一天开始啦。","铭哥，起床的时间到啦。","先醒醒神，今天按你的节奏来。") else pool("ring.general","你留好的提醒到了，看看这件事吧。","铭哥，这件事的提醒时间到了。","记好的安排到点啦，需要的话也能稍后提醒。")
}
