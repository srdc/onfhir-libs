package io.onfhir.client.testutil

import org.json4s.JsonAST.{JArray, JField, JInt, JObject, JString, JValue}

/**
 * Plain builders for the spec conformant FHIR payloads used by the Tier 1
 * contract suites. No cleverness on purpose - every builder just assembles a
 * JObject that a real FHIR server could return.
 */
object CannedResponses {

  /** One entry of a history bundle */
  case class HistoryEntry(
    method: String,
    url: String,
    status: String,
    lastModified: String,
    etag: Option[String] = None,
    resource: Option[JObject] = None)

  /** One entry of a batch/transaction response bundle */
  case class TransactionEntry(
    fullUrl: Option[String],
    status: String,
    resource: Option[JObject] = None,
    location: Option[String] = None,
    etag: Option[String] = None,
    lastModified: Option[String] = None,
    outcome: Option[JObject] = None)

  /** A minimal resource of the given type */
  def resource(resourceType: String, id: String, fields: JField*): JObject =
    JObject(List("resourceType" -> JString(resourceType), "id" -> JString(id)) ++ fields.toList)

  def patient(id: String, fields: JField*): JObject = resource("Patient", id, fields: _*)

  /** meta element carrying a versionId, for version aware update tests */
  def meta(versionId: String, lastUpdated: Option[String] = None): JField =
    "meta" -> JObject(
      List("versionId" -> JString(versionId)) ++
        lastUpdated.map(value => "lastUpdated" -> JString(value)).toList
    )

  /**
   * A searchset bundle.
   *
   * @param total    Bundle.total
   * @param matches  Resources served with search.mode = match
   * @param includes Resources served with search.mode = include
   * @param nextLink Url of the next page if the result set is paginated
   * @param selfLink Url of this page
   */
  def searchSetBundle(
    total: Long,
    matches: Seq[JObject] = Nil,
    includes: Seq[JObject] = Nil,
    nextLink: Option[String] = None,
    selfLink: Option[String] = None): JObject =
    JObject(
      List[JField](
        "resourceType" -> JString("Bundle"),
        "type" -> JString("searchset"),
        "total" -> JInt(total)
      ) ++
        links(selfLink, nextLink) ++
        List[JField](
          "entry" -> JArray(
            (matches.map(searchEntry(_, "match")) ++ includes.map(searchEntry(_, "include"))).toList
          )
        )
    )

  /** A history bundle */
  def historyBundle(entries: Seq[HistoryEntry], nextLink: Option[String] = None, selfLink: Option[String] = None): JObject =
    JObject(
      List[JField](
        "resourceType" -> JString("Bundle"),
        "type" -> JString("history")
      ) ++
        links(selfLink, nextLink) ++
        List[JField]("entry" -> JArray(entries.map(historyEntry).toList))
    )

  /** A batch or transaction response bundle */
  def transactionResponseBundle(bundleType: String, entries: Seq[TransactionEntry]): JObject =
    JObject(List[JField](
      "resourceType" -> JString("Bundle"),
      "type" -> JString(bundleType),
      "entry" -> JArray(entries.map(transactionEntry).toList)
    ))

  /** A Parameters resource holding the given parameter entries */
  def parametersResource(parameters: JObject*): JObject =
    JObject(List[JField](
      "resourceType" -> JString("Parameters"),
      "parameter" -> JArray(parameters.toList)
    ))

  /** Parameters.parameter with a primitive or complex value[x] */
  def valueParam(name: String, valueField: String, value: JValue): JObject =
    JObject(List[JField]("name" -> JString(name), s"value$valueField" -> value))

  /** Parameters.parameter carrying a whole resource */
  def resourceParam(name: String, resource: JObject): JObject =
    JObject(List[JField]("name" -> JString(name), "resource" -> resource))

  /** Parameters.parameter with child parts */
  def multiParam(name: String, parts: JObject*): JObject =
    JObject(List[JField]("name" -> JString(name), "part" -> JArray(parts.toList)))

  /** An OperationOutcome resource */
  def operationOutcome(issues: JObject*): JObject =
    JObject(List[JField](
      "resourceType" -> JString("OperationOutcome"),
      "issue" -> JArray(issues.toList)
    ))

  /** One OperationOutcome.issue */
  def issue(severity: String, code: String, diagnostics: String, expression: Seq[String] = Nil): JObject =
    JObject(
      List[JField](
        "severity" -> JString(severity),
        "code" -> JString(code),
        "diagnostics" -> JString(diagnostics)
      ) ++
        (if (expression.isEmpty) Nil
         else List[JField]("expression" -> JArray(expression.map(JString(_)).toList)))
    )

  private def links(selfLink: Option[String], nextLink: Option[String]): List[JField] = {
    val entries =
      selfLink.map(url => link("self", url)).toList ++ nextLink.map(url => link("next", url)).toList
    if (entries.isEmpty) Nil else List[JField]("link" -> JArray(entries))
  }

  private def link(relation: String, url: String): JObject =
    JObject(List[JField]("relation" -> JString(relation), "url" -> JString(url)))

  private def searchEntry(resource: JObject, mode: String): JObject =
    JObject(List[JField](
      "resource" -> resource,
      "search" -> JObject(List[JField]("mode" -> JString(mode)))
    ))

  private def historyEntry(entry: HistoryEntry): JObject =
    JObject(
      entry.resource.map(value => "resource" -> value).toList ++
        List[JField](
          "request" -> JObject(List[JField](
            "method" -> JString(entry.method),
            "url" -> JString(entry.url)
          )),
          "response" -> JObject(
            List[JField](
              "status" -> JString(entry.status),
              "lastModified" -> JString(entry.lastModified)
            ) ++ entry.etag.map(value => "etag" -> JString(value)).toList
          )
        )
    )

  private def transactionEntry(entry: TransactionEntry): JObject =
    JObject(
      entry.fullUrl.map(value => "fullUrl" -> JString(value)).toList ++
        entry.resource.map(value => "resource" -> value).toList ++
        List[JField](
          "response" -> JObject(
            List[JField]("status" -> JString(entry.status)) ++
              entry.location.map(value => "location" -> JString(value)).toList ++
              entry.etag.map(value => "etag" -> JString(value)).toList ++
              entry.lastModified.map(value => "lastModified" -> JString(value)).toList ++
              entry.outcome.map(value => "outcome" -> value).toList
          )
        )
    )
}
