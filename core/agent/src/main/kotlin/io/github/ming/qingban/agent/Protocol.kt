package io.github.ming.qingban.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.github.ming.qingban.domain.AlarmSpec
import io.github.ming.qingban.domain.RecurrenceSpec

@Serializable data class ChatLine(val role: String, val text: String, val options: List<String> = emptyList(), val sources: List<String> = emptyList(), val changeId: String? = null, val sourceIds: List<String> = emptyList(), val sourceVersions: Map<String,Int> = emptyMap())
@Serializable data class LocalDraft(val kind: String, val title: String, val date: String? = null, val time: String? = null, val period: String? = null, val needsReminder: Boolean = false, val deadlineDate: String? = null, val alarm: AlarmSpec = AlarmSpec(), val recurrence: RecurrenceSpec? = null, val pendingField: String = "time")
@Serializable data class PendingAgentTurn(val request: String, val requestId: String, val results: List<JsonObject> = emptyList(), val inFlight: ProposedAction? = null)
@Serializable data class AgentSession(val history: List<ChatLine> = emptyList(), val pendingLocal: LocalDraft? = null, val proposalId: String? = null, val pausedLocal: List<LocalDraft> = emptyList(), val pendingUserRequest: String? = null, val lastItemId: String? = null, val pausedRequests: List<String> = emptyList(), val activeTurn: PendingAgentTurn? = null, val interruptedTurns: List<PendingAgentTurn> = emptyList())
@Serializable data class ProposedAction(val tool: String, val args: JsonObject)
@Serializable data class Proposal(val id: String, val summary: String, val changes: List<ProposedAction>, val expiresAt: Long, val approved: Boolean = false)
data class AgentRequest(val systemPrompt: String, val history: List<ChatLine>, val context: JsonObject)
interface ModelClient {
    suspend fun available(): Boolean
    suspend fun decide(request: AgentRequest): String
}
class ModelOutputException(val errorCode: String, message: String) : IllegalStateException(message)
class DecisionFormatException(val errorCode: String, message: String) : IllegalArgumentException(message)
data class ModelConfiguration(val baseUrl: String, val model: String, val apiKey: String, val jsonMode: Boolean = true)
fun interface CredentialProvider { suspend fun configuration(): ModelConfiguration? }
sealed interface Decision {
    data class Say(val text: String) : Decision
    data class Ask(val question: String, val options: List<String>) : Decision
    data class Call(val tool: String, val args: JsonObject) : Decision
}
object DecisionParser {
    private val json = Json
    fun parse(raw: String): Decision {
        fun checkFormat(valid: Boolean, code: String, message: String) {
            if(!valid) throw DecisionFormatException(code,message)
        }
        checkFormat(raw.length <= 32_000,"decision_too_large","模型输出超过 32000 字符，请缩短单次方案")
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = try { json.parseToJsonElement(clean) } catch(e: Exception) {
            throw DecisionFormatException("invalid_decision_json","返回内容不是完整的合法 JSON；不要输出解释文字或未闭合的对象")
        }
        val obj = parsed as? JsonObject ?: throw DecisionFormatException("invalid_decision_object","决策必须是一个 JSON 对象，不能是数组或文本")
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw DecisionFormatException("missing_decision_field","缺少非空文本字段 $key")
        fun fields(vararg names: String) = checkFormat(obj.keys == names.toSet(),"invalid_decision_fields","决策字段必须恰好为 ${names.joinToString("、")}")
        return when(text("mode")) {
            "say" -> { fields("mode","text"); Decision.Say(text("text")) }
            "ask" -> {
                fields("mode","question","options")
                val array=obj["options"] as? JsonArray ?: throw DecisionFormatException("invalid_question_options","options 必须是字符串数组，可以为空")
                val options = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    ?: throw DecisionFormatException("invalid_question_options","options 中每一项必须是文本") }
                checkFormat(options.size <= 3 && options.distinct().size == options.size && options.all { it.isNotBlank() },"invalid_question_options","options 最多 3 个互不重复的非空选项")
                Decision.Ask(text("question"), options)
            }
            "call" -> { fields("mode","tool","args"); Decision.Call(text("tool"),obj["args"] as? JsonObject
                ?: throw DecisionFormatException("invalid_tool_arguments","args 必须是 JSON 对象，不能是数组或文本")) }
            else -> throw DecisionFormatException("unknown_decision_mode","mode 仅支持 say、ask、call")
        }
    }
}
val TOOL_GUIDE = """
你只能调用以下工具，参数未提供表示不修改，null 表示清除。未知字段会被拒绝。
query_items: {query?:string,date_from?:YYYY-MM-DD,date_to?:YYYY-MM-DD,kind?:note|task|alarm,status?:open|completed|cancelled,habit_only?:boolean,offset?:integer,limit?:1..100}。返回 total/has_more；有更多记录时分页，不能把第一页当作全部。
get_item: {item_id:string}。ID、version 均来自真实查询结果。
search_knowledge: {query:string,include_historical?:boolean}。仅返回用户允许当前模型读取的资料。
create_item: {kind:note|task|alarm,title:string,detail?:string,schedule?:{date?:YYYY-MM-DD,time?:HH:mm,period?:morning|afternoon|evening,range_start?:date,range_end?:date,recurrence?:{frequency:weekly|cn_workday|cn_restday|shift,weekdays?:[1..7],start_date:date,end_date?:date,work_days?:1..365,rest_days?:1..365,cycle_anchor?:date}},deadline?:{date:date,time?:HH:mm},reminder?:{at_local?:YYYY-MM-DDTHH:mm,offset_minutes?:非负整数},alarm_preset_id?:default,label?:string}。
重复闹钟无需指定 schedule.date，只需 time 与 recurrence。weekly 必须有 weekdays：每天=[1,2,3,4,5,6,7]、周一至周五=[1,2,3,4,5]、周末=[6,7]；cn_workday/cn_restday 不传 weekdays，只按中国大陆已公布的调休安排，cn_restday 含普通周末；不把法定工作日转换成周一至周五。shift 不传 weekdays，必须提供 work_days、rest_days 和 cycle_anchor（周期首个上班日），anchor 不晚于 start_date。轮班缺任一参数时询问，不猜周期或锚点。end_date 可不传；不将未覆盖的年份伪装成可用法定日历。
update_item: {item_id:string,expected_version:integer,scope:this_occurrence|this_and_future|whole_series,occurrence_date?:YYYY-MM-DD,patch:{title?:string,detail?:string,schedule?:object|null,deadline?:object|null,reminder?:object|null,label?:string,status?:open|completed}}。重复事项单次变更支持 schedule/status/checklist，并需 occurrence_date；此后系列用 this_and_future 并给 occurrence_date，schedule 保留 recurrence。
cancel_item: {item_id:string,expected_version:integer,scope:this_occurrence|this_and_future|whole_series,occurrence_date?:date}。
delete_item: {item_id:string,expected_version:integer,scope:this_occurrence|whole_series,occurrence_date?:date}。始终生成用户确认预览，确认后删除并停止提醒，可整批撤销。whole_series 删除整个重复系列；this_occurrence 只移除指定那次。用户只说删除重复习惯且范围不明先问；批量删除用 propose_changes，不把取消当删除。
propose_changes: {summary:string,changes:[{tool:create_item|update_item|cancel_item|delete_item,args:object}]}，最多 50 项，仅生成预览。多个时段、不同活动各一个 create_item；同一方案的 create_item.args 可带唯一 plan_key（字母、数字、下划线），depends_on 可用 @plan_key 引用同方案前置任务，保存时程序转换为真实 ID。plan_key 只能出现在方案内，普通 create_item 不接受。
split_task: {item_id:string,expected_version:integer}。仅用户要求拆开已有大块任务时使用；读取其明确时间段，预览多个独立任务、原任务归档及后续依赖更新；用户确认后统一保存并可撤销。没有明确时间段时需先询问，然后 propose_changes，不猜执行时间。
review_overdue: {}。生成或读取本地日终调整卡片；返回 review（或 null）。23:00 自动生成，手机受后台限制时下次打开补检查；点击卡片“同意并调整”后才保存。不能由模型声称确认或直接改期绕过卡片。同意仅作用于用户勾选的任务，截止日期不改变。
apply_proposal: {proposal_id:string}，仅能执行程序已登记用户确认的方案。
write_memory: {operation:set|disable|delete,key:string,value?:string,scope?:string|object,basis:user_declared|confirmed_suggestion,valid_until?:YYYY-MM-DD}。仅用户明确要求长期记住或程序确认的候选可写入。
undo_change: {change_id?:string}。
create_item 和 update_item.patch 还可包含 alarm:{play_audio?:boolean,vibrate?:boolean,headphones_only?:boolean,snooze_minutes?:integer}。只改所给字段，不能传入任意音乐文件路径。“别外放”设 headphones_only=true；“纯振动”设 play_audio=false,vibrate=true。
闹钟默认预设 ID 是 default（系统铃声加振动）；不编造其他预设 ID。不要将任务通知与音乐闹钟混淆。
工具结果 partial 表示已保存但部分能力未启用，error 表示未成功，approval_pending 表示等待用户确认。
create_item 与 update_item.patch 可含 depends_on:[真实事项ID]、duration_minutes:1..1440、pinned:boolean。未知时长不猜；依赖不可成环。已确认方案的事项在一个事务中保存，参数错误则全部不保存；系统提醒在事务后调度，权限不足仍可能 partial。整批可用返回的 change_id 撤销。
可包含 checklist:[{text:string,completed?:boolean}]，最多 30 个条目；用户未说完成时 completed=false。清单与整件事项的完成状态独立。没有授权不要将全部清单勾选。
focus_status: {}。返回真实计时、专注设置、最近记录和 elapsed_ms，单位为毫秒。
focus_control: {action:start|pause|resume|reset|finish|rest,mode?:pomodoro|stopwatch,minutes?:1..240,task_id?:真实事项ID}。默认25分钟或用户已有设置；只有 start 使用 mode/minutes/task_id。已有运行或暂停的计时不允许覆盖，需先问是否结束；pause 不丢进度，resume 继续；reset 清空当前计时但保留统计；finish 提前结束番茄不记完成，正计时保存时长；rest 只能在完整番茄结束后开始休息。不自动完成关联任务。
focus_settings: {focus_minutes?:1..240,rest_minutes?:1..60,long_rest_minutes?:1..120,long_rest_every?:1..12,notify?:boolean,immersive?:boolean}。未给字段保留；新时长只影响之后的轮次。partial 表示计时保存但通知或精确提醒权限待开启。
navigate: {page:today|calendar|memo|alarm|focus|library|memory|settings|habits,date?:YYYY-MM-DD,view?:month|week|three_day|day}。habits 打开习惯与督促；只在用户要求打开或切换页面时使用，view 只用于日历。创建任务和启动计时不需要跳页面。
habit_status: {query?:string}。查询真实习惯 ID/version、近7天已打卡/计划数、连续计划日和今天是否打卡。打卡不证明实际行为。
习惯待办使用 create_item kind=task、schedule.recurrence、label=习惯；无截止时长期出现，完成当天用 update_item this_occurrence/status=completed，不完成整个系列。重复规则与闹钟相同，可以没有钟点和提醒。不自动重复旧的一次性生活事项。只有用户明确说独立打卡习惯时用下面的 habit_manage；不要两套重复创建。
habit_manage: {operation:create|update|pause|resume|check|uncheck|delete,habit_id?:真实ID,expected_version?:integer,title?:string,weekdays?:[1..7],time?:HH:mm|null,remind?:boolean,date?:YYYY-MM-DD}。create 默认每天、无提醒；update/pause/resume 必须查询 ID/version；未给字段保留。check/uncheck 默认今天，只允许近7天计划内日期，需用户明确说已完成、打卡或撤销。暂停保留记录，delete 仅在明确要求删除时清除该习惯和打卡记录。提醒需有钟点。只建立习惯不创建重复待办、不写长期偏好、不自动启用督促。
gentle_settings: {enabled:boolean}。仅用户明确开启/关闭温和督促时使用。已开启通知的未完成任务，在原安排结束后30/60分钟最多补提醒2次/天，22:00—08:00不打扰；有时长则按结束时间，没有则按开始/独立提醒时间。所有规则本地运行，不能强制阻断手机，不能保证系统省电状态下准点。
list_sounds: {}。返回已导入／缓存的真实 sound ID 与名称。
set_alarm_sound: {item_id:string,expected_version:integer,sound_id:string|null}。仅闹钟可改，sound_id 只能来自 list_sounds；null 为系统铃声。用户明确要求改铃声后使用，不能任意传路径。在线选歌、网易云跳转和音频导入仍需用户在铃声选择页操作。
get_preferences: {}。查询当前有效且允许本模型读取的偏好。
manage_knowledge: {operation:list|enable|disable|delete,source_id?:string}。先 list 得到真实 ID；多份同名资料先问。只有明确停用、启用或删除要求才能变更，不能擅自扩大资料的远程读取权限。
""".trimIndent()
