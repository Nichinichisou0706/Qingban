package io.github.ming.qingban.model

import io.github.ming.qingban.agent.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.ZoneId

/** Optional computer-side evaluation. Credentials come only from the invoking process environment. */
fun main()=runBlocking {
    val base=System.getenv("QINGBAN_MODEL_BASE_URL") ?: error("Set QINGBAN_MODEL_BASE_URL, QINGBAN_MODEL_NAME and QINGBAN_MODEL_API_KEY in the invoking process.")
    val name=System.getenv("QINGBAN_MODEL_NAME") ?: error("Missing QINGBAN_MODEL_NAME")
    val key=System.getenv("QINGBAN_MODEL_API_KEY") ?: error("Missing QINGBAN_MODEL_API_KEY")
    val preset=AgentCoordinator::class.java.getResourceAsStream("/agent-system.txt")?.bufferedReader()?.use { it.readText() } ?: error("Missing packaged preset")
    val client=CompatibleModelClient { ModelConfiguration(base,name,key,System.getenv("QINGBAN_MODEL_JSON_MODE")!="false") }
    val evaluator=ModelEvaluator(client,preset,Clock.system(ZoneId.of(System.getenv("QINGBAN_MODEL_TIMEZONE") ?: "Asia/Shanghai")))
    val results=evaluator.run()+evaluator.runConversations()
    results.forEach { println("${if(it.passed) "PASS" else "FAIL"} ${it.name}: ${it.detail}") }
    println("${results.count { it.passed }}/${results.size} synthetic scenarios passed. No application data was accessed.")
    check(results.all { it.passed }) { "Model evaluation failed" }
}
