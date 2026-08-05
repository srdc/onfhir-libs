package io.onfhir.client.testutil

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.onfhir.api.model.OrderedQuery
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JObject, JValue}
import org.json4s.jackson.JsonMethods
import org.specs2.specification.BeforeAfterAll

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * A canned HTTP response served by [[MockFhirServer]].
 *
 * @param status       HTTP status code
 * @param body         Response body (empty string means no body)
 * @param contentType  Content-Type header value
 * @param extraHeaders Additional response headers e.g. Location, ETag
 */
case class MockResponse(
  status: Int,
  body: String,
  contentType: String = MockFhirServer.fhirJsonContentType,
  extraHeaders: Map[String, String] = Map.empty)

/**
 * A single request exactly as it arrived on the wire.
 *
 * @param method   HTTP method
 * @param rawPath  Raw (still percent-encoded) request path
 * @param rawQuery Raw (still percent-encoded) query string if any
 * @param headers  All request headers
 * @param body     Request body decoded as UTF-8
 */
case class RecordedRequest(
  method: String,
  rawPath: String,
  rawQuery: Option[String],
  headers: Map[String, Seq[String]],
  body: String) {

  /** First value of the given header, case insensitive */
  def header(name: String): Option[String] = headerValues(name).headOption

  /** All values of the given header, case insensitive */
  def headerValues(name: String): Seq[String] =
    headers.collectFirst { case (key, values) if key.equalsIgnoreCase(name) => values }.getOrElse(Nil)

  /** Decoded query parameters preserving repetitions */
  def queryParams: Map[String, List[String]] = OrderedQuery.parse(rawQuery.getOrElse("")).toMultiMap

  /** Request path relative to the mock server's FHIR base url e.g. /Patient/p1 */
  def relativePath: String = MockFhirServer.relativize(rawPath)

  /** Body parsed as JSON (may be an array e.g. for JSON Patch) */
  def bodyJson: JValue = JsonMethods.parse(body)

  /** Body parsed as a JSON object */
  def bodyResource: JObject = body.parseJson
}

/**
 * A JDK based mock FHIR server for Tier 1 contract tests.
 *
 * Binds to 127.0.0.1 on an ephemeral port, records every request and answers
 * with scripted responses. Never reaches the external network.
 */
class MockFhirServer {
  private val requestLog: mutable.ListBuffer[RecordedRequest] = mutable.ListBuffer.empty
  private val singleStubs: mutable.Map[(String, String), MockResponse] = mutable.Map.empty
  private val sequenceStubs: mutable.Map[(String, String), mutable.Queue[MockResponse]] = mutable.Map.empty
  private var defaultStub: MockResponse = MockFhirServer.defaultResponse
  private var tokenCount: Int = 0
  private var server: HttpServer = _
  private var port: Int = 0

  /** FHIR server base url, always ending with /fhir */
  def baseUrl: String = s"http://127.0.0.1:$port${MockFhirServer.basePath}"

  /** OAuth2 token endpoint served by the same mock server */
  def tokenEndpointUrl: String = s"http://127.0.0.1:$port${MockFhirServer.tokenPath}"

  def start(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(MockFhirServer.basePath, new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = handleFhirRequest(exchange)
    })
    server.createContext(MockFhirServer.tokenPath, new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = handleTokenRequest(exchange)
    })
    server.start()
    port = server.getAddress.getPort
  }

  def stop(): Unit = if (server != null) server.stop(0)

  /** All recorded requests in arrival order */
  def requests: Seq[RecordedRequest] = synchronized(requestLog.toList)

  /** The most recently recorded request */
  def lastRequest: RecordedRequest = requests.last

  def requestCount: Int = synchronized(requestLog.size)

  def tokenRequestCount: Int = synchronized(tokenCount)

  /** Forget all recorded requests and scripted responses */
  def reset(): Unit = synchronized {
    requestLog.clear()
    singleStubs.clear()
    sequenceStubs.clear()
    defaultStub = MockFhirServer.defaultResponse
    tokenCount = 0
  }

  /**
   * Always answer the given method plus path with the given response.
   *
   * @param method     HTTP method e.g. GET
   * @param pathSuffix Path relative to [[baseUrl]] e.g. /Patient/p1 (/ for the base itself)
   */
  def stub(
    method: String,
    pathSuffix: String)(
    status: Int,
    body: String,
    contentType: String = MockFhirServer.fhirJsonContentType,
    extraHeaders: Map[String, String] = Map.empty): Unit = synchronized {
    singleStubs.update(key(method, pathSuffix), MockResponse(status, body, contentType, extraHeaders))
  }

  /**
   * Answer successive calls to the given method plus path with the given responses in order.
   * Once the queue is drained the single stub (or the default) applies again.
   */
  def stubSequence(method: String, pathSuffix: String)(responses: MockResponse*): Unit = synchronized {
    sequenceStubs.update(key(method, pathSuffix), mutable.Queue.from(responses))
  }

  /** Response used when no stub matches */
  def stubDefault(response: MockResponse): Unit = synchronized {
    defaultStub = response
  }

  private def key(method: String, pathSuffix: String): (String, String) =
    method.toUpperCase -> MockFhirServer.relativize(pathSuffix)

  private def handleFhirRequest(exchange: HttpExchange): Unit = {
    val recorded = record(exchange)
    val response = synchronized {
      val stubKey = key(recorded.method, recorded.rawPath)
      sequenceStubs
        .get(stubKey)
        .filter(_.nonEmpty)
        .map(_.dequeue())
        .orElse(singleStubs.get(stubKey))
        .getOrElse(defaultStub)
    }
    respond(exchange, response)
  }

  private def handleTokenRequest(exchange: HttpExchange): Unit = {
    record(exchange)
    synchronized(tokenCount += 1)
    respond(exchange, MockResponse(200, MockFhirServer.tokenResponseBody, "application/json"))
  }

  private def record(exchange: HttpExchange): RecordedRequest = {
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    val headers = exchange.getRequestHeaders.asScala.map { case (name, values) =>
      name -> values.asScala.toSeq
    }.toMap
    val recorded = RecordedRequest(
      method = exchange.getRequestMethod,
      rawPath = exchange.getRequestURI.getRawPath,
      rawQuery = Option(exchange.getRequestURI.getRawQuery),
      headers = headers,
      body = body
    )
    synchronized(requestLog.append(recorded))
    recorded
  }

  private def respond(exchange: HttpExchange, response: MockResponse): Unit = {
    val bytes = response.body.getBytes(StandardCharsets.UTF_8)
    if (bytes.nonEmpty)
      exchange.getResponseHeaders.add("Content-Type", response.contentType)
    response.extraHeaders.foreach { case (name, value) => exchange.getResponseHeaders.add(name, value) }
    if (bytes.isEmpty) exchange.sendResponseHeaders(response.status, -1)
    else {
      exchange.sendResponseHeaders(response.status, bytes.length)
      exchange.getResponseBody.write(bytes)
    }
    exchange.close()
  }
}

object MockFhirServer {
  val basePath = "/fhir"
  val tokenPath = "/token"
  val fhirJsonContentType = "application/fhir+json; charset=UTF-8"
  val accessToken = "access-1"

  private val tokenResponseBody =
    s"""{"access_token":"$accessToken","token_type":"Bearer","expires_in":3600}"""

  private val defaultResponse: MockResponse =
    MockResponse(200, """{"resourceType":"Patient","id":"p1"}""")

  /**
   * Reduce an absolute or base relative path to a path relative to the FHIR base url.
   * Accepts /fhir/Patient/p1, /Patient/p1 and Patient/p1 alike.
   */
  def relativize(path: String): String = {
    val withoutBase = if (path.startsWith(basePath)) path.drop(basePath.length) else path
    if (withoutBase.isEmpty || withoutBase == "/") "/"
    else if (withoutBase.startsWith("/")) withoutBase
    else "/" + withoutBase
  }
}

/**
 * Lifecycle helper starting one [[MockFhirServer]] per specification.
 */
trait WithMockFhirServer extends BeforeAfterAll {
  protected val mockServer: MockFhirServer = new MockFhirServer()

  override def beforeAll(): Unit = mockServer.start()

  override def afterAll(): Unit = mockServer.stop()
}
