package io.onfhir.path

import org.json4s.JValue
import org.json4s.JsonAST._

import scala.util.Try

object FhirPathValueTransformer {

  /**
   * Transform a JValue to FhirPathResult
   *
   * @param v
   * @return
   */
  def transform(v: JValue, isContentFhir: Boolean = true): Seq[FhirPathResult] = {
    v match {
      case JArray(arr) => arr.flatMap(i => transform(i, isContentFhir))
      case jobj: JObject => Seq(FhirPathComplex(jobj))
      case JInt(i) => Seq(FhirPathNumber(BigDecimal(i)))
      case JDouble(num) => Seq(FhirPathNumber(num))
      //Keep JDecimal and JLong exact: FhirPathNumber is BigDecimal backed, and narrowing them
      //through Double silently collapses distinct integral values above 2^53
      case JDecimal(num) => Seq(FhirPathNumber(num))
      case JLong(num) => Seq(FhirPathNumber(BigDecimal(num)))
      case JString(s) if isContentFhir && s.headOption.exists(_.isDigit) => Seq(resolveFromString(s))
      case JString(s) => Seq(FhirPathString(s))
      case JBool(b) => Seq(FhirPathBoolean(b))
      case _ => Nil
    }
  }

  private def resolveFromString(str: String): FhirPathResult = {
    Try(FhirPathLiteralEvaluator.parseFhirDateTimeBestExceptYear(str)).toOption
      .map(FhirPathDateTime)
      .getOrElse(
        //If it seems to be a FHIR time, try to parse it
        if (
          (str.length == 5 && str.apply(2) == ':') ||
            (str.length == 8 && str.apply(2) == ':' && str.apply(5) == ':') ||
            (str.length > 9 && str.apply(9) == '.' && str.length < 13)
        )
          Try(FhirPathLiteralEvaluator.parseFhirTime(str)).toOption
            .map(t => FhirPathTime(t._1, t._2))
            .getOrElse(FhirPathString(str))
        else
          FhirPathString(str)
      )
  }


  def serializeToJson(result: Seq[FhirPathResult]): JValue = {
    val jsonValues = result.map(_.toJson)
    jsonValues.length match {
      case 0 => JNull
      case 1 => jsonValues.head
      case _ => JArray(jsonValues.toList)
    }
  }
}
