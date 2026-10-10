package io.github.ming.qingban.model

import com.sun.net.httpserver.HttpServer
import io.github.ming.qingban.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class CompatibleModelClientTest {
    @Test fun adapterUsesDeclaredProtocolAndJsonMode() = runBlocking {
        val received = AtomicReference<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            received.set(Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject)
            val result = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", "{\"mode\":\"say\",\"text\":\"你好\"}") }) }) }) }.toString().toByteArray()
            exchange.sendResponseHeaders(200, result.size.toLong()); exchange.responseBody.use { it.write(result) }
        }
        server.start()
        try {
            val client = CompatibleModelClient { ModelConfiguration("http://127.0.0.1:${server.address.port}/v1", "test-model", "fake-test-key") }
            val result = client.decide(AgentRequest("preset", listOf(ChatLine("user", "你好")), buildJsonObject { put("current_user_request", "你好") }))
            assertTrue(DecisionParser.parse(result) is Decision.Say)
            assertEquals("json_object", received.get()["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(4096,received.get()["max_tokens"]!!.jsonPrimitive.int)
            assertEquals("system", received.get()["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        } finally { server.stop(0) }
    }
    @Test fun remotePlainHttpIsRejectedBeforeRequest() = runBlocking { val client = CompatibleModelClient { ModelConfiguration("http://example.com/v1", "test", "fake") }; var failed = false; try { client.decide(AgentRequest("preset", emptyList(), buildJsonObject {})) } catch(e: IllegalArgumentException) { failed = true }; assertTrue(failed) }
    @Test fun incompleteConfigurationIsOffline() = runBlocking { assertFalse(CompatibleModelClient { null }.available()) }
    private suspend fun fixture(path: String="/v1/chat/completions",reply: String,action: suspend (String)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext(path) { exchange -> val bytes=reply.toByteArray(); exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        server.start(); try { action("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
    private fun request()=AgentRequest("preset",emptyList(),buildJsonObject {})
    @Test fun customApiPrefixIsPreserved()=runBlocking { fixture("/api/v3/chat/completions","""{"choices":[{"message":{"content":"ok"}}]}""") { base -> assertEquals("ok",CompatibleModelClient { ModelConfiguration("$base/api/v3","test","fake") }.decide(request())) } }
    @Test fun completeEndpointIsAccepted()=runBlocking { fixture(reply="""{"choices":[{"message":{"content":"ok"}}]}""") { base -> assertEquals("ok",CompatibleModelClient { ModelConfiguration("$base/v1/chat/completions","test","fake") }.decide(request())) } }
    @Test fun oversizedBodyIsRejected()=runBlocking { fixture(reply="x".repeat(1_000_001)) { base -> assertTrue(runCatching { CompatibleModelClient { ModelConfiguration(base,"test","fake") }.decide(request()) }.isFailure) } }
    @Test fun nullContentIsRejected()=runBlocking { fixture(reply="""{"choices":[{"message":{"content":null}}]}""") { base -> assertTrue(runCatching { CompatibleModelClient { ModelConfiguration(base,"test","fake") }.decide(request()) }.isFailure) } }
    @Test fun lengthFinishReasonReportsTruncationInsteadOfReturningPartialJson()=runBlocking {
        fixture(reply="""{"choices":[{"finish_reason":"length","message":{"content":"{\"mode\":\"call\",\"tool\":\"propose_changes\""}}]}""") { base ->
            val failure=runCatching { CompatibleModelClient { ModelConfiguration(base,"test","fake") }.decide(request()) }.exceptionOrNull()
            assertNotNull("A length-limited response must not reach the JSON decision parser",failure)
            assertTrue(failure!!.message.orEmpty().contains("截断"))
        }
    }
    @Test fun credentialsInUrlAreRejected()=runBlocking { assertTrue(runCatching { CompatibleModelClient { ModelConfiguration("http://fake:secret@localhost/v1","test","fake") }.decide(request()) }.isFailure) }
    @Test fun cancelledRequestDoesNotWaitForHttpTimeout()=runBlocking {
        val started=CompletableDeferred<Unit>(); val release=java.util.concurrent.CountDownLatch(1)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/chat/completions") { exchange -> started.complete(Unit); release.await(5,java.util.concurrent.TimeUnit.SECONDS); exchange.close() }
        server.start()
        try { val client=CompatibleModelClient { ModelConfiguration("http://127.0.0.1:${server.address.port}","test","fake") }; val job=launch { client.decide(request()) }; withTimeout(3000) { started.await(); job.cancelAndJoin() }; assertTrue(job.isCancelled) } finally { release.countDown(); server.stop(0) }
    }
}
