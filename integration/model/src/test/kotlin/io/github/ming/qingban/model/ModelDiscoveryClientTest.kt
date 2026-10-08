package io.github.ming.qingban.model

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ModelDiscoveryClientTest {
    private suspend fun fixture(path: String, body: String, status: Int = 200, action: suspend (String, AtomicInteger) -> Unit) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            requests.incrementAndGet()
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer synthetic-key", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { action("http://127.0.0.1:${server.address.port}", requests) } finally { server.stop(0) }
    }
    @Test fun rootAddressFindsModelsAndDeduplicatesValidIds() = runBlocking {
        fixture("/v1/models", """{"data":[{"id":"z-chat"},{"id":"A-chat"},{"id":"z-chat"},{"id":null},{"name":"wrong"},{"id":" "},{"id":123}]}""") { base, requests ->
            assertEquals(listOf("A-chat", "z-chat"), ModelDiscoveryClient().discover(base, "synthetic-key"))
            assertEquals(1, requests.get())
        }
    }
    @Test fun customPrefixAndCompleteEndpointsArePreserved() = runBlocking {
        fixture("/api/v3/models", """{"data":[{"id":"demo"}]}""") { base, requests ->
            listOf("/api/v3", "/api/v3/chat/completions", "/api/v3/models", "/api/v3/").forEach { suffix ->
                assertEquals(listOf("demo"), ModelDiscoveryClient().discover(base + suffix, "synthetic-key"))
            }
            assertEquals(4, requests.get())
        }
    }
    @Test fun authorizationAndMissingListHaveUsefulErrors() = runBlocking {
        fixture("/v1/models", "secret-from-server", 401) { base, _ ->
            val error = runCatching { ModelDiscoveryClient().discover(base, "synthetic-key") }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("密钥无效")); assertFalse(error.message!!.contains("secret-from-server"))
        }
        fixture("/v1/models", "missing", 404) { base, _ ->
            assertTrue(runCatching { ModelDiscoveryClient().discover(base, "synthetic-key") }.exceptionOrNull()!!.message!!.contains("手动"))
        }
    }
    @Test fun redirectIsNotFollowedAndDoesNotLeakKey() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0); val leaked = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/elsewhere")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/elsewhere") { exchange -> leaked.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try { assertTrue(runCatching { ModelDiscoveryClient().discover("http://127.0.0.1:${server.address.port}", "synthetic-key") }.isFailure); assertEquals(0, leaked.get()) }
        finally { server.stop(0) }
    }
    @Test fun malformedEmptyAndOversizedListsAreRejected() = runBlocking {
        listOf("bad-json", """{"data":[]}""", """{"models":[{"id":"demo"}]}""", "x".repeat(4_000_001)).forEach { body ->
            fixture("/v1/models", body) { base, _ -> assertTrue(runCatching { ModelDiscoveryClient().discover(base, "synthetic-key") }.isFailure) }
        }
    }
    @Test fun unsafeAddressesAndBlankKeyAreRejected() = runBlocking {
        listOf("http://example.com/v1", "https://user:password@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#fragment").forEach { base ->
            assertTrue(runCatching { ModelDiscoveryClient().discover(base, "synthetic-key") }.isFailure)
        }
        assertTrue(runCatching { ModelDiscoveryClient().discover("https://example.com/v1", "") }.isFailure)
    }
    @Test fun cancelledDiscoveryReleasesTheRequest() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val started = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/v1/models") { exchange -> started.complete(Unit); release.await(5, java.util.concurrent.TimeUnit.SECONDS); exchange.close() }
        server.start()
        try {
            val job = launch { ModelDiscoveryClient().discover("http://127.0.0.1:${server.address.port}", "synthetic-key") }
            withTimeout(3000) { started.await(); job.cancelAndJoin() }; assertTrue(job.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }
}
