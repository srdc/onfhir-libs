package io.onfhir.client.model

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.time.Duration

/**
 * Contract of the transport settings owned by the JDK HTTP client.
 */
@RunWith(classOf[JUnitRunner])
class ClientHttpSettingsTest extends Specification {

  "ClientHttpSettings defaults" should {
    "connect in ten seconds, never time out the whole request and retry five times" in {
      val settings = ClientHttpSettings.default

      settings.connectTimeout mustEqual Duration.ofSeconds(10)
      settings.requestTimeout must beNone
      settings.maxRetries mustEqual 5
      settings.sslContext must beNone
    }
  }

  "ClientHttpSettings.fromConfig" should {
    "read the values from a nested http block" in {
      val config = ConfigFactory.parseString(
        """
          |serverBaseUrl = "http://127.0.0.1:8080/fhir"
          |http {
          |  connect-timeout = 3s
          |  request-timeout = 15s
          |  max-retries = 2
          |}
          |""".stripMargin)

      val settings = ClientHttpSettings.fromConfig(config)

      settings.connectTimeout mustEqual Duration.ofSeconds(3)
      settings.requestTimeout must beSome(Duration.ofSeconds(15))
      settings.maxRetries mustEqual 2
    }

    "read the values from direct keys without an http wrapper" in {
      val config = ConfigFactory.parseString(
        """
          |connect-timeout = 250ms
          |request-timeout = 1m
          |max-retries = 0
          |""".stripMargin)

      val settings = ClientHttpSettings.fromConfig(config)

      settings.connectTimeout mustEqual Duration.ofMillis(250)
      settings.requestTimeout must beSome(Duration.ofMinutes(1))
      settings.maxRetries mustEqual 0
    }

    "fall back to the defaults for missing keys" in {
      val settings = ClientHttpSettings.fromConfig(ConfigFactory.parseString("""http { }"""))

      settings mustEqual ClientHttpSettings.default
    }

    "fall back to the defaults for an empty configuration" in {
      val settings = ClientHttpSettings.fromConfig(ConfigFactory.empty())

      settings mustEqual ClientHttpSettings.default
    }
  }

  "ClientHttpSettings validation" should {
    "reject a zero connect timeout" in {
      ClientHttpSettings(connectTimeout = Duration.ZERO) must throwA[IllegalArgumentException]
    }

    "reject a negative connect timeout" in {
      ClientHttpSettings(connectTimeout = Duration.ofSeconds(-1)) must throwA[IllegalArgumentException]
    }

    "reject a non positive request timeout" in {
      ClientHttpSettings(requestTimeout = Some(Duration.ZERO)) must throwA[IllegalArgumentException]
      ClientHttpSettings(requestTimeout = Some(Duration.ofMillis(-5))) must throwA[IllegalArgumentException]
    }

    "reject a negative retry count" in {
      ClientHttpSettings(maxRetries = -1) must throwA[IllegalArgumentException]
    }
  }
}
