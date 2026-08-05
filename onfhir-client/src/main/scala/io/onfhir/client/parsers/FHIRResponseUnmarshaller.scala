package io.onfhir.client.parsers

import io.onfhir.api.client.{FhirClientException, OperationOutcomeParser}
import io.onfhir.api.model._
import io.onfhir.api.{FHIR_CONTENT_TYPES, Resource}
import io.onfhir.client.model.ClientHttpResponse
import io.onfhir.util.DateTimeUtil
import io.onfhir.util.JsonFormatter._

import java.net.URI
import java.nio.charset.{Charset, StandardCharsets}

object FHIRResponseUnmarshaller {
  def unmarshallResponse(httpResponse: ClientHttpResponse): FHIRResponse = {
    val responseBody = parseBody(httpResponse)
    val common = FHIRResponse(
      httpStatus = httpResponse.status,
      responseBody = responseBody,
      location = firstHeader(httpResponse, "Location").map(URI.create),
      lastModified = firstHeader(httpResponse, "Last-Modified").map(DateTimeUtil.parseHttpDate),
      newVersion = firstHeader(httpResponse, "ETag").flatMap(parseVersion),
      authenticateHeader = firstHeader(httpResponse, "WWW-Authenticate").map(AuthenticateChallenge.parse),
      xCorrelationId = firstHeader(httpResponse, "X-Correlation-Id"),
      xIntermediary = firstHeader(httpResponse, "X-Intermediary")
    )
    if (httpResponse.status.isFailure())
      common.copy(outcomeIssues = responseBody.map(OperationOutcomeParser.parseIssues).getOrElse(Nil))
    else common
  }

  private def parseBody(httpResponse: ClientHttpResponse): Option[Resource] = {
    if (httpResponse.body.isEmpty) None
    else {
      val contentTypeValue = firstHeader(httpResponse, "Content-Type")
        .getOrElse(throw FhirClientException("FHIR response body has no Content-Type header"))
      val mediaType = FhirMediaType.parse(contentTypeValue)
      if (mediaType.normalizedMainType == "application" && mediaType.normalizedSubType == "fhir+xml")
        throw FhirClientException("XML response bodies are not supported in OnFhirClient")
      if (mediaType.normalizedMainType != "application" || mediaType.normalizedSubType != "fhir+json")
        throw FhirClientException(s"Unsupported FHIR response content type: $contentTypeValue")
      val charsetName = mediaType.parameterValues("charset").headOption.getOrElse(StandardCharsets.UTF_8.name())
      Some(new String(httpResponse.body.toArray, Charset.forName(charsetName)).parseJson)
    }
  }

  private def parseVersion(value: String): Option[String] =
    EntityTagCondition.parse(value) match {
      case EntityTagList(tags) => tags.headOption.map(_.value)
      case AnyEntityTag => None
    }

  private def firstHeader(response: ClientHttpResponse, name: String): Option[String] =
    response.headers.values(name).headOption
}
