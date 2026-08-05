package io.onfhir.api.client

import io.onfhir.api.Resource
import io.onfhir.api.model.OutcomeIssue
import io.onfhir.api.util.FHIRUtil
import org.json4s.JsonAST.JObject

/**
 * Parses FHIR OperationOutcome content into OutcomeIssue models.
 * Shared by the response unmarshaller and the batch/transaction bundle so both
 * read spec conformant issues the same way: details is a CodeableConcept
 * (first coding code), details.text is the fallback for diagnostics and
 * location (DSTU2/STU3 style) is the fallback for expression.
 */
private[onfhir] object OperationOutcomeParser {

  def parseIssues(operationOutcome: Resource): Seq[OutcomeIssue] =
    FHIRUtil.extractValueOption[Seq[JObject]](operationOutcome, "issue")
      .getOrElse(Nil)
      .map(parseIssue)

  private def parseIssue(outcomeIssue: JObject): OutcomeIssue = OutcomeIssue(
    severity = FHIRUtil.extractValue[String](outcomeIssue, "severity"),
    code = FHIRUtil.extractValue[String](outcomeIssue, "code"),
    details = FHIRUtil.extractValueOptionByPath[Seq[String]](outcomeIssue, "details.coding.code").getOrElse(Nil).headOption,
    diagnostics = FHIRUtil.extractValueOption[String](outcomeIssue, "diagnostics")
      .orElse(FHIRUtil.extractValueOptionByPath[String](outcomeIssue, "details.text")),
    //A missing repetitive element extracts as an empty Seq, so treat empty as absent before falling back to 'location'
    expression = FHIRUtil.extractValueOption[Seq[String]](outcomeIssue, "expression")
      .filter(_.nonEmpty)
      .orElse(FHIRUtil.extractValueOption[Seq[String]](outcomeIssue, "location"))
      .getOrElse(Nil)
  )
}
