package com.apollographql.mockserver.test

import com.apollographql.mockserver.MockRequestBase
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.MockServerHandler
import com.apollographql.mockserver.TcpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CustomHandlerTest {
  @Test
  fun customHandler() = runTest {

    val mockResponse0 = MockResponse.Builder()
        .body("Hello, World! 000")
        .statusCode(404)
        .addHeader("Content-Type", "text/plain")
        .build()
    val mockResponse1 = MockResponse.Builder()
        .body("Hello, World! 001")
        .statusCode(200)
        .addHeader("X-Test", "true")
        .build()

    val mockServerHandler = object : MockServerHandler {
      override fun handle(request: MockRequestBase): MockResponse {
        return when (request.path) {
          "/0" -> mockResponse0
          "/1" -> mockResponse1
          else -> error("Unexpected path: ${request.path}")
        }
      }
    }

    MockServer.Builder().handler(mockServerHandler).build().use { mockServer ->
      val client = HttpClient()

      var httpResponse = client.get(mockServer.url() + "1")
      assertMockResponse(mockResponse1, httpResponse)

      httpResponse = client.get(mockServer.url() + "0")
      assertMockResponse(mockResponse0, httpResponse)

      httpResponse = client.get(mockServer.url() + "1")
      assertMockResponse(mockResponse1, httpResponse)
    }
  }

  private val testDispatcher = StandardTestDispatcher()

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun customHandler_concurrent() = runTest(testDispatcher) {
    val mockResponse0 = MockResponse.Builder()
      .body("Hello, World! 000")
      .statusCode(404)
      .addHeader("Content-Type", "text/plain")
      .build()
    val mockResponse1 = MockResponse.Builder()
      .body("Hello, World! 001")
      .statusCode(200)
      .addHeader("X-Test", "true")
      .delayMillis(1000)
      .build()

    val mockServerHandler = object : MockServerHandler {
      override fun handle(request: MockRequestBase): MockResponse {
        return when (request.path) {
          "/0" -> mockResponse0
          "/1" -> mockResponse1
          else -> error("Unexpected path: ${request.path}")
        }
      }
    }

    println("BUILDING MOCK SERVER")
    val server = MockServer.Builder()
      .handler(mockServerHandler)
      .tcpServer(TcpServer(0, testDispatcher))
      .scope(CoroutineScope(testDispatcher + SupervisorJob()))
      .build()
    server.use { mockServer ->
      println("BUILDING CLIENT")
      val client = HttpClient()

      println("KICKING OFF REQUESTS")
      val call0 = async {
        println("CALLING GET 0")
        client.get(mockServer.url() + "0")
      }
      val call1 = async {
        println("CALLING GET 1")
        client.get(mockServer.url() + "1")
      }
      println("REQUESTS CREATED")
      assertFalse(call0.isCompleted)
      assertFalse(call1.isCompleted)

      println("RUNNING CURRENT")
      runCurrent()
      println("ASSERTING THAT CALL 0 COMPLETED")
      assertTrue(call0.isCompleted)
      assertFalse(call1.isCompleted)

      println("ADVANCING CLOCK")
      advanceTimeBy(2.seconds)
      println("ASSERTING THAT BOTH FINISHED")
      assertTrue(call0.isCompleted)
      assertTrue(call1.isCompleted)
      println("ASSERTING RESPONSES")
      val response0 = call0.await()
      val response1 = call1.await()
      assertEquals("Hello World! 000", response0.bodyAsText())
      assertEquals("Hello World! 001", response1.bodyAsText())
    }
  }
}
