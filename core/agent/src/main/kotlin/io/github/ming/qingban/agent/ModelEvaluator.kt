package io.github.ming.qingban.agent

import kotlinx.serialization.json.*
import java.time.Clock
import java.time.LocalDate
import io.github.ming.qingban.domain.*

data class EvaluationResult(val name: String, val passed: Boolean, val detail: String)
class ModelEvaluator(private val client: ModelClient, private val preset: String, private val clock: Clock) {
    suspend fun runDetailedConversations(only: String?=null): List<EvaluationResult> {
        require(client.available()) { "请先保存模型配置" }
        val tomorrow=LocalDate.now(clock).plusDays(1).toString()
        data class Scene(val name: String,val inputs: List<String>,val check: suspend (EvaluationSandbox)->Boolean,val approve: Boolean=false)
        val scenes=listOf(
            Scene("三个时段保存为独立任务",listOf("安排明天09:00-11:00高数第五章、14:30-17:00通原第二章、18:00-21:30概率论第一章，分开成独立任务，先给预览，暂不提醒"),{ s -> val rows=s.itemRows.value.filter { it.status==ItemStatus.OPEN }; rows.size==3 && rows.map { it.schedule?.time }.toSet()==setOf("09:00","14:30","18:00") && rows.map { it.durationMinutes }.toSet()==setOf(120,150,210) && rows.all { it.kind==ItemKind.TASK && it.schedule?.date==tomorrow && it.reminder==null } },true),
            Scene("先后关系使用真实事项",listOf("先写方案，再发客户；明天做，暂不定具体时间，不需要提醒。给两个独立任务的方案"),{ s -> val rows=s.itemRows.value.filter { it.status==ItemStatus.OPEN }; rows.size==2 && rows.any { it.dependsOn.isNotEmpty() && it.dependsOn.all { id -> rows.any { r -> r.id==id } } } },true),
            Scene("时间表备忘不自动排程",listOf("只保存备忘：09:00-11:00看书，14:00-16:00运动，这是想法，之后再安排"),{ s -> s.itemRows.value.singleOrNull()?.let { it.kind==ItemKind.NOTE && it.schedule==null && it.reminder==null && "09:00" in it.detail && "14:00" in it.detail }==true }),
            Scene("每天闹钟保留全部星期",listOf("每天八点叫我起床，只振动"),{ s -> s.itemRows.value.singleOrNull()?.let { it.kind==ItemKind.ALARM && it.schedule?.date==null && it.schedule?.time=="08:00" && it.schedule?.recurrence?.weekdays==(1..7).toSet() && !it.alarm.playAudio && it.alarm.vibrate }==true }),
            Scene("专注暂停保留这一轮",listOf("开始一个25分钟番茄钟","暂停番茄钟"),{ s -> s.services.focus.state.value.run?.let { it.status=="paused" && it.targetMillis==25*60_000L }==true && s.itemRows.value.isEmpty() }),
            Scene("习惯打卡可撤销且无重复任务",listOf("建立独立的每天阅读打卡习惯，不提醒","阅读习惯打卡","撤销阅读习惯打卡"),{ s -> s.services.habits.state.value.let { state -> state.habits.size==1 && state.checks.singleOrNull()?.done==false } && s.itemRows.value.isEmpty() }),
            Scene("长期习惯是单个重复待办",listOf("创建每天阅读习惯，长期保持，先不用提醒"),{ s -> s.itemRows.value.singleOrNull()?.let { it.isHabitTask && it.schedule?.recurrence?.weekdays==(1..7).toSet() && it.schedule?.recurrence?.endDate==null && it.reminder==null }==true && s.services.habits.state.value.habits.isEmpty() }),
            Scene("习惯完成仅作用于今天",listOf("创建每天阅读习惯，先不用提醒","今天阅读习惯完成了，只完成今天这次"),{ s -> val day=LocalDate.now(clock); s.itemRows.value.singleOrNull()?.let { it.status==ItemStatus.OPEN && ScheduleEngine.onDate(it,day)?.status==ItemStatus.COMPLETED && ScheduleEngine.onDate(it,day.plusDays(1))?.status==ItemStatus.OPEN }==true }),
            Scene("删除必须等待用户确认",listOf("保存备忘：临时验收备忘","删除临时验收备忘"),{ s -> s.itemRows.value.singleOrNull()?.deleted==false && s.services.sessions.read("current_proposal")!=null }),
            Scene("温和督促开关只改本地规则",listOf("开启温和督促","关闭温和督促"),{ s -> !s.services.habits.state.value.gentle && s.itemRows.value.isEmpty() }),
            Scene("缺钟点仍会追问闹钟",listOf("明晚叫我检查门窗"),{ s -> s.itemRows.value.isEmpty() }),
            Scene("任务通知与闹钟独立",listOf("明天18:30通知提醒我拿快递，另外明天08:00设起床闹钟"),{ s -> val rows=s.itemRows.value.filter { it.status==ItemStatus.OPEN }; rows.size==2 && rows.any { it.kind==ItemKind.TASK && it.schedule?.time=="18:30" && it.reminder!=null } && rows.any { it.kind==ItemKind.ALARM && it.schedule?.time=="08:00" } })
        )
        return scenes.filter { only==null || it.name==only }.map { scene ->
            try {
                val sandbox=EvaluationSandbox(clock); val c=AgentCoordinator(sandbox.services,client,preset)
                var approvalSafe=true
                scene.inputs.forEach { input -> c.send(input) }
                if(scene.approve) {
                    val p=c.proposal()
                    approvalSafe=p!=null && sandbox.itemRows.value.isEmpty()
                    if(p!=null) c.approveProposal(p.id)
                } else if(scene.name=="任务通知与闹钟独立") {
                    // A compound explicit request may validly use a preview rather than two immediate writes.
                    c.proposal()?.let { p -> approvalSafe=sandbox.itemRows.value.isEmpty(); c.approveProposal(p.id) }
                }
                val passed=c.session.value.activeTurn==null && approvalSafe && scene.check(sandbox)
                val evidence=sandbox.itemRows.value.joinToString("；") { "${it.kind}:${it.title} ${it.schedule?.date} ${it.schedule?.time} ${if(it.reminder!=null) "有通知" else "无通知"}" }
                EvaluationResult(scene.name,passed,if(passed) "独立数据中的执行结果符合要求" else "结果未通过：${evidence.ifBlank { "未写入事项" }}；待确认方案=${c.proposal()!=null}；${c.session.value.history.lastOrNull()?.text?.take(120).orEmpty()}")
            } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; EvaluationResult(scene.name,false,e.message?.take(120) ?: "请求失败") }
        }
    }
    suspend fun runConversations(): List<EvaluationResult> {
        require(client.available()) { "请先保存模型配置" }
        val today=LocalDate.now(clock)
        data class Scenario(val name:String,val input:List<String>,val check:(List<PlanItem>)->Boolean)
        val scenarios=listOf(
            Scenario("追问后完成耳机闹钟",listOf("明早叫我起床","八点，只走耳机，没连耳机就振动")) { rows -> rows.size==1 && rows.single().kind==ItemKind.ALARM && rows.single().schedule?.date==today.plusDays(1).toString() && rows.single().schedule?.time=="08:00" && rows.single().alarm.headphonesOnly },
            Scenario("连续改期只保留一件事",listOf("明天买牛奶","把买牛奶改到后天")) { rows -> rows.size==1 && rows.single().status==ItemStatus.OPEN && rows.single().schedule?.date==today.plusDays(2).toString() },
            Scenario("撤销不会重复新增",listOf("记一下：想换一把键盘","撤销刚才的记录")) { rows -> rows.size==1 && rows.single().status==ItemStatus.CANCELLED }
        )
        return scenarios.map { scene ->
            try {
                val sandbox=EvaluationSandbox(clock); val coordinator=AgentCoordinator(sandbox.services,client,preset)
                scene.input.forEach { coordinator.send(it) }
                val pass=coordinator.session.value.activeTurn==null && scene.check(sandbox.itemRows.value)
                EvaluationResult(scene.name,pass,if(pass) "独立测试数据中的多轮操作符合要求" else "多轮结果不符合要求；未触及你的数据")
            } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; EvaluationResult(scene.name,false,e.message?.take(120) ?: "请求失败") }
        }
    }
    suspend fun run(): List<EvaluationResult> {
        require(client.available()) { "请先保存模型配置" }
        data class Scenario(val name: String, val input: String, val check: (Decision) -> Boolean)
        val scenarios = listOf(
            Scenario("缺闹钟时间会追问", "明早叫我起床") { it is Decision.Ask },
            Scenario("普通待办不强加提醒", "明天买牛奶") { it is Decision.Call && it.tool == "create_item" && it.args["kind"]?.jsonPrimitive?.content == "task" && it.args["schedule"]?.jsonObject?.get("time").let { v -> v == null || v == JsonNull } && it.args["reminder"].let { v -> v == null || v == JsonNull } },
            Scenario("无日期想法可记备忘", "记一下：想换一把键盘，时间还没想好") { it is Decision.Call && it.tool == "create_item" && it.args["kind"]?.jsonPrimitive?.content in setOf("note","task") && it.args["schedule"].let { v -> v == null || v == JsonNull } },
            Scenario("同名目标先查询或追问", "把开会改到周五") { it is Decision.Ask || (it is Decision.Call && it.tool in setOf("query_items","get_item")) },
            Scenario("习惯不能擅自写入", "我最近几次晚上健身，是不是更适合我？") { it !is Decision.Call || it.tool in setOf("query_items","search_knowledge") },
            Scenario("查询不会改日程", "今天有什么安排？") { it is Decision.Call && it.tool == "query_items" }
        )
        return scenarios.map { scene ->
            try {
                val context = buildJsonObject { put("runtime_context",buildJsonObject { put("local_date", LocalDate.now(clock).toString()); put("timezone",clock.zone.id); put("user_display_name","铭哥") }); put("current_user_request",scene.input); put("confirmed_preferences",JsonArray(emptyList())); put("available_items",JsonArray(emptyList())); put("recent_tool_results",JsonArray(emptyList())) }
                val decision = DecisionParser.parse(client.decide(AgentRequest(PromptPreset.render(preset,clock), emptyList(), context)))
                val pass = scene.check(decision); EvaluationResult(scene.name,pass,if(pass) "首步决策符合要求" else "首步决策不符合要求，请调整模型或预设")
            } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; EvaluationResult(scene.name,false,e.message?.take(120) ?: "请求失败") }
        }
    }
}
