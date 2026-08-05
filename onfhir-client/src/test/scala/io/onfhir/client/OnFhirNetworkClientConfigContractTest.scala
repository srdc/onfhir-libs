package io.onfhir.client

import com.typesafe.config.ConfigFactory
import io.onfhir.api.client.FhirClientException
import io.onfhir.client.testutil.{MockFhirServer, WithMockFhirServer}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.util.Base64
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * Contract of building an OnFhirNetworkClient from a typesafe Config, plus base
 * url validation as it surfaces to the caller.
 */
@RunWith(classOf[JUnitRunner])
class OnFhirNetworkClientConfigContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def config(body: String) = ConfigFactory.parseString(body)

  "OnFhirNetworkClient built from a config with basic authorization" should {
    "apply the Basic Authorization header to every request" in {
      mockServer.reset()
      val client = OnFhirNetworkClient(config(
        s"""
           |serverBaseUrl = "${mockServer.baseUrl}"
           |authz {
           |  method = basic
           |  username = alice
           |  password = secret
           |}
           |""".stripMargin))

      Await.result(client.read("Patient", "p1").execute(), 5.seconds)

      val expected = "Basic " + Base64.getEncoder.encodeToString("alice:secret".getBytes("UTF-8"))
      mockServer.lastRequest.header("Authorization") must beSome(expected)
      client.getBaseUrl() mustEqual mockServer.baseUrl
    }
  }

  "OnFhirNetworkClient built from a config with oauth2 authorization" should {
    "fetch the token once and apply it as a Bearer header" in {
      mockServer.reset()
      val client = OnFhirNetworkClient(config(
        s"""
           |serverBaseUrl = "${mockServer.baseUrl}"
           |authz {
           |  method = oauth2
           |  client_id = client-1
           |  client_secret = secret-1
           |  scopes = ["system/Patient.read"]
           |  token_endpoint = "${mockServer.tokenEndpointUrl}"
           |  token_endpoint_auth_method = client_secret_basic
           |}
           |""".stripMargin))

      Await.result(Future.sequence(Seq(
        client.read("Patient", "p1").execute(),
        client.read("Patient", "p1").execute()
      )), 5.seconds)

      mockServer.tokenRequestCount mustEqual 1
      mockServer.lastRequest.header("Authorization") must beSome(s"Bearer ${MockFhirServer.accessToken}")
    }
  }

  "OnFhirNetworkClient built from a config with an unsupported authorization method" should {
    "fail at construction with a FhirClientException" in {
      OnFhirNetworkClient(config(
        s"""
           |serverBaseUrl = "${mockServer.baseUrl}"
           |authz { method = kerberos }
           |""".stripMargin)) must throwA[FhirClientException]
    }
  }

  "OnFhirNetworkClient built from a config without an authz block" should {
    "send unauthenticated requests" in {
      mockServer.reset()
      val client = OnFhirNetworkClient(config(s"""serverBaseUrl = "${mockServer.baseUrl}""""))

      Await.result(client.read("Patient", "p1").execute(), 5.seconds)

      mockServer.lastRequest.header("Authorization") must beNone
    }

    "use the transport defaults when no http block is given" in {
      val client = OnFhirNetworkClient(config(s"""serverBaseUrl = "${mockServer.baseUrl}""""))

      client.httpSettings mustEqual io.onfhir.client.model.ClientHttpSettings.default
    }

    "read the transport settings from an http block" in {
      val client = OnFhirNetworkClient(config(
        s"""
           |serverBaseUrl = "${mockServer.baseUrl}"
           |http { connect-timeout = 2s, max-retries = 1 }
           |""".stripMargin))

      client.httpSettings.connectTimeout mustEqual java.time.Duration.ofSeconds(2)
      client.httpSettings.maxRetries mustEqual 1
    }
  }

  "An invalid FHIR server base url" should {
    "fail the request future for a non http scheme" in {
      val thrown = Try(Await.result(
        OnFhirNetworkClient("ftp://127.0.0.1/fhir").read("Patient", "p1").execute(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      thrown.getCause must beAnInstanceOf[IllegalArgumentException]
    }

    "fail the request future for a relative base url" in {
      val thrown = Try(Await.result(
        OnFhirNetworkClient("/fhir").read("Patient", "p1").execute(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
    }

    "fail the request future when the base url carries a query" in {
      val thrown = Try(Await.result(
        OnFhirNetworkClient("http://127.0.0.1/fhir?tenant=a").read("Patient", "p1").execute(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      thrown.getCause must beAnInstanceOf[IllegalArgumentException]
    }

    "fail the request future when the base url carries a fragment" in {
      val thrown = Try(Await.result(
        OnFhirNetworkClient("http://127.0.0.1/fhir#frag").read("Patient", "p1").execute(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
    }

    "tolerate a trailing slash on the base url" in {
      mockServer.reset()
      val client = OnFhirNetworkClient(mockServer.baseUrl + "/")

      Await.result(client.read("Patient", "p1").execute(), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Patient/p1"
    }
  }
}
