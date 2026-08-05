package io.onfhir.template

import io.onfhir.api.service.{IFhirIdentityService, IFhirTerminologyService}
import io.onfhir.expression.{FhirExpression, FhirExpressionException, IFhirExpressionLanguageHandler}
import io.onfhir.path._
import io.onfhir.util.JsonFormatter._
import org.json4s.{JArray, JInt, JNothing, JNull, JObject, JString, JValue}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
 * Expression handler for FHIR template language (mustache like) that we devise within onFhir to create dynamic FHIR contents based on placeholder FHIR Path expressions within the template
 * @param staticContextParams       Context params that will be supplied to every evaluation with this instance
 * @param functionLibraryFactories  Function libraries for FHIR Path expression evaluation
 * @param terminologyService        In order to use FHIR Path terminology service functions, the service itself
 * @param identityService           In order to use FHIR Path identity service function, the service itself
 * @param isSourceContentFhir       Whether the source content will be FHIR or not
 */
class FhirTemplateExpressionHandler(
                                     staticContextParams: Map[String, JValue] = Map.empty,
                                     functionLibraryFactories:Map[String, IFhirPathFunctionLibraryFactory] = Map.empty,
                                     terminologyService:Option[IFhirTerminologyService] = None,
                                     identityService:Option[IFhirIdentityService] = None,
                                     isSourceContentFhir:Boolean = false
                                   )  extends IFhirExpressionLanguageHandler with Serializable {
  /**
   * Supported language mime type
   */
  override val languageSupported: String = "application/fhir-template+json"

  /**
   * Regular expression for a placeholder expression that is the whole JSON value
   */
  private val templateFullPlaceholderValuePattern = """^\{\{(([\* \+ \?]?) )?(((?!\{\{).)+)\}\}$""".r
  /**
   * Regular expression for a placeholder expression that is within the JSON values
   */
  private val templatePlaceholderInside = """\{\{(((?!\{\{).)+)\}\}""".r
  /**
   * Regular expression for a cardinality marker leading a placeholder expression e.g. '? ' in {{? Observation.id}}.
   * '*' and '?' can never start a FHIR Path expression, while '+' is only treated as a marker when followed by
   * whitespace so that a unary plus e.g. {{+5}} is still evaluated.
   */
  private val templateCardinalityMarker = """^\s*([\*\?]|\+\s)""".r
  /**
   * Regular expression for Template Section Field
   */
  private val templateSectionField = """^\{\{#(\w+)\}\}$""".r
  /**
   * Regular expression for Template Section Value
   */
  private val templateSectionExpressionValue = """^\{\{(((?!\{\{).)+)\}\}$""".r

  /**
   * Base FHIR path evaluator
   */
  val fhirPathEvaluator:FhirPathEvaluator = {
    var temp = FhirPathEvaluator.apply(staticContextParams, functionLibraryFactories, isContentFhir = isSourceContentFhir)
    terminologyService.foreach(ts => temp = temp.withTerminologyService(ts))
    identityService.foreach(is => temp = temp.withIdentityService(is))
    temp
  }

  /**
   * Validate the expression
   *
   * The template content is expected either as parsed JSON in 'value' (the common case) or as a
   * template string in 'expression'. Anything that [[evaluateExpression]] can render is accepted here, so
   * validation only rejects expressions that carry no template content at all. Placeholder syntax and the
   * embedded FHIR Path expressions are not validated ahead of evaluation.
   * @param expression  Parsed expression
   */
  def validateExpression(expression: FhirExpression):Unit = {
    resolveTemplateContent(expression)
  }

  /**
   * Resolve the template content of the given expression
   * @param expression  Parsed expression
   * @return            Parsed JSON template from 'value', or the template string from 'expression' as a JString
   */
  private def resolveTemplateContent(expression: FhirExpression):JValue = {
    expression.value
      .filter(v => v != JNull && v != JNothing)
      .orElse(expression.expression.filter(_.nonEmpty).map(JString(_)))
      .getOrElse(throw FhirExpressionException(s"Missing FHIR template content! Provide the parsed JSON template in 'value' or a template string in 'expression'."))
  }

  /**
   * Template expressions can not be used as boolean expressions
   * @param expression
   * @param contextParams
   * @param input
   * @param ex
   * @throws
   * @return
   */
  @throws[FhirExpressionException]
  def satisfies(expression: FhirExpression, contextParams: Map[String, JValue], input:JValue = JNothing)(implicit ex:ExecutionContext): Future[Boolean] = {
    throw FhirExpressionException(s"Do not use FHIR Templates for applicability checks'!")
  }

  /**
   * Evaluate the given template
   * @param expression    Template content
   * @param contextParams Supplied context parameters for the evaluation
   * @param input         Given input content for evaluation
   * @return
   */
  override def evaluateExpression(expression: FhirExpression, contextParams: Map[String, JValue], input: JValue)(implicit ex: ExecutionContext): Future[JValue] = {
    Future.apply {
      val fhirTemplate = resolveTemplateContent(expression)
      //Get the final evaluator
      val evaluator = if(contextParams.isEmpty) fhirPathEvaluator else fhirPathEvaluator.copy(environmentVariables = fhirPathEvaluator.environmentVariables ++ contextParams)

      val filledTemplate = evaluateTemplate(fhirTemplate, evaluator, input)

      removeEmptyFields(filledTemplate)
    }
  }

  /**
   * Evaluate the template recursively going on every leaf
   * @param template            Template content
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Supplied input content for the evaluation
   * @return
   */
  private def evaluateTemplate(template:JValue, fhirPathEvaluator:FhirPathEvaluator, input: JValue):JValue = {
    template match {
      // The value is given by a placeholder
      case JString(s) if s.startsWith("{{") && s.endsWith("}}") =>
        templateFullPlaceholderValuePattern.findFirstMatchIn(s) match {
          case None =>
            handleInternalMatches(s, fhirPathEvaluator, input)
          case Some(m) =>
            handleCompleteValueMatch(m, fhirPathEvaluator, input)
        }

      //There may be some placeholders within the string
      case JString(s) =>
        handleInternalMatches(s, fhirPathEvaluator, input)

      //A section definition within template (like mustache sections) for repetitive or optional sections
      //JSON field order is not significant, so the section variable declaration may come in either position
      case JObject(fields) if fields.exists(_._1.startsWith("{{#")) =>
        fields.partition(_._1.startsWith("{{#")) match {
          case (List((sectionField, JString(sectionStatement))), List((valueField, valuePart))) =>
            handleTemplateSection(sectionField, sectionStatement, valueField, valuePart, fhirPathEvaluator, input)
          case _ =>
            throw FhirExpressionException(
              s"Invalid FHIR template section! A section is a JSON object with exactly two fields; a section variable declaration e.g. \"{{#member}}\": \"{{<FHIR path statement>}}\" and a section value e.g. \"{{*}}\": {...}.",
              Some(JObject(fields).toJson))
        }
      //Go recursive on fields
      case JObject(fields) =>
        JObject(
          fields.map(f => {
            val fieldValue = evaluateTemplate(f._2, fhirPathEvaluator, input)
            f._1 -> fieldValue
          })
        )
      case JArray(vs) =>
        JArray(vs.flatMap(
          evaluateTemplate(_, fhirPathEvaluator, input) match {
            case arr:JArray => arr.arr  //If the inner part returns an array merge it with others as generally we don't have array of arrays in FHIR
            case JNull | JNothing => Nil
            case oth => List(oth)
          }
        ))
      //Otherwise the same
      case oth => oth
    }
  }

  /**
   * Handle template section
   * @param sectionField        Section element field name e.g. {{#member}}
   * @param sectionStatement    Section element field value (FHIR Path statement) e.g.  {{CareTeam.participant.member}}
   * @param valueField          Section value field name e.g. {{?}} or {{*}}
   * @param valuePart           Section value field value
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Current input for evaluation
   * @return
   */
  private def handleTemplateSection(sectionField:String, sectionStatement:String, valueField:String, valuePart:JValue, fhirPathEvaluator:FhirPathEvaluator,  input: JValue):JValue = {
    //Resolve section variable name e.g. {{#member}} -> member
    val sectionFieldVar = templateSectionField.findFirstMatchIn(sectionField) match {
      case None => throw FhirExpressionException(s"Invalid FHIR template section field! It should be in format {{#<variable-name>}} e.g. {{#member}}.", Some(sectionField))
      case Some(m) => m.group(1)
    }
    //Resolve section variable FHIR Path statement e.g. {{CareTeam.participant.member}}
    val sectionPathStatement = templateSectionExpressionValue.findFirstMatchIn(sectionStatement) match {
      case None => throw FhirExpressionException(s"Invalid FHIR template section statement! It should be in format {{<FHIR path statement>}}.", Some(sectionStatement))
      case Some(s) => s.group(1)
    }
    //Evaluate the section variable statement
    val sectionResults =
      try {
        fhirPathEvaluator.evaluate(sectionPathStatement, input).map(_.toJson)
      } catch {
        case t:Throwable => throw FhirExpressionException("Problem while evaluating section statement!", Some(sectionPathStatement), Some(t))
      }

    val finalResults =
      valueField match {
        //If it is optional provide the whole section results as context param
        case "{{?}}" if sectionResults.nonEmpty =>
          Seq(
            evaluateTemplate(
              valuePart,
              fhirPathEvaluator.withEnvironmentVariable(sectionFieldVar, sectionResults match {
                case Seq(single) => single
                case oth => JArray(oth.toList)
              }),
              input
            )
          )
        //For each entry for the section variable evaluate the section value part, by providing each element of section results as context param
        case _ =>
          sectionResults
            .zipWithIndex
            .map {
              case (sectionResult, i) =>
                evaluateTemplate(valuePart,
                  fhirPathEvaluator
                    .withEnvironmentVariable(sectionFieldVar, sectionResult)
                    .withEnvironmentVariable("sectionIndex", JInt(i+1)),
                  input
                )
            }
      }

    valueField match {
      //section is an array
      case "{{*}}" => if(finalResults.isEmpty) JNull else JArray(finalResults.toList)
      case "{{+}}" =>
        if(finalResults.isEmpty)
          throw  FhirExpressionException(s"Template section returns empty although value is marked with '+' (1-n cardinality)!", Some(valuePart.toJson))
        else
          JArray(finalResults.toList)
      case "{{?}}" => finalResults.headOption.getOrElse(JNull)
      case _ => throw  FhirExpressionException(s"Invalid FHIR template section value field! Use '{{*}}' or '{{+}}' for arrays and '{{?}}' for optional JSON objects.", Some(valueField))
    }
  }

  /**
   * Remove empty arrays, objects or null valued fields from the given JValue
   * @param value
   * @return
   */
  private def removeEmptyFields(value:JValue):JValue = {
    value match {
      case JObject(elems) =>
        elems
          .map(el => el._1 -> removeEmptyFields(el._2))
          .filterNot(_._2 == JNull) match {
          case l if l.isEmpty => JNull
          case oth => JObject(oth)
        }
      case JArray(arr) =>
        arr
          .map(removeEmptyFields)
          .filterNot(_ == JNull) match {
          case l if l.isEmpty => JNull
          case oth => JArray(oth)
        }
      case oth => oth
    }
  }

  /**
   * Handle internal placeholders within String values
   * @param strValue            String value possibly with placeholders within
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Input for FHIR path evaluations
   * @return
   */
  private def handleInternalMatches(strValue:String, fhirPathEvaluator:FhirPathEvaluator, input:JValue):JString = {
    def findMatch(m:Regex.Match):String = {
      val fhirPathExpression = m.group(1)
      if(templateCardinalityMarker.findFirstIn(fhirPathExpression).nonEmpty)
        throw FhirExpressionException(s"Cardinality markers ('?', '*', '+') are not supported for placeholders used within FHIR string values; such a placeholder must return exactly one primitive value!", Some(fhirPathExpression))
      val fhirPathResult:Seq[FhirPathResult] =
        try {
          fhirPathEvaluator.evaluate(fhirPathExpression, input)
        } catch {
          case t:Throwable => throw FhirExpressionException("Problem while evaluating internal FHIR Path expression!", Some(fhirPathExpression),Some(t))
        }
      fhirPathResult match {
        case Seq(fhirPathResult) => fhirPathResult match {
          case FhirPathComplex(_) | FhirPathQuantity(_, _) =>
            throw FhirExpressionException(s"FHIR path expression returns complex JSON object although it is used within a FHIR string value!", Some(fhirPathExpression))
          case FhirPathString(s) => s
          case n:FhirPathNumber if n.isInteger() => n.v.toLong.toString
          case FhirPathNumber(n) => "" + n.toDouble.toString
          case FhirPathBoolean(b) => "" + b
          case dt:FhirPathDateTime =>
            dt.toJson.toJson.dropRight(1).drop(1)
          case t:FhirPathTime => t.toJson.toJson.dropRight(1).drop(1)
        }
        case _ =>
          throw FhirExpressionException(s"FHIR path expression returns multiple or empty result although it is used within a FHIR string value!", Some(fhirPathExpression))
      }
    }

    //Fill the placeholder values; quote the resolved values as the replacement is otherwise interpreted
    //(a '$' would be read as a group reference and a '\' as an escape) e.g. a resolved value '100$'
    val filledValue = templatePlaceholderInside.replaceAllIn(strValue, replacer = m => Regex.quoteReplacement(findMatch(m)))
    JString(filledValue)
  }

  /**
   * Handle placeholders that represents the Json value completely e.g. "value" : "{{Observation.valueQuantity.value}}"
   * @param m                   Matched regular expression group
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Input for FHIR path evaluations
   * @return
   */
  private def handleCompleteValueMatch(m:Regex.Match, fhirPathEvaluator:FhirPathEvaluator, input:JValue):JValue = {
    val indicator = m.group(2)
    val isArray = indicator == "*" || indicator == "+"
    val isOptional = indicator == "*" || indicator == "?"
    val fhirPathExpression = m.group(3)

    val fhirPathResult:Seq[FhirPathResult] =
      try {
        fhirPathEvaluator.evaluate(fhirPathExpression, input)
      } catch {
        case t:Throwable => throw FhirExpressionException("Problem while evaluating FHIR Path expression!", Some(fhirPathExpression), Some(t))
      }

    val result = fhirPathResult.map(_.toJson)

    if(!isOptional && result.isEmpty)
      throw FhirExpressionException(s"FHIR path expression returns empty although value is not marked as optional! Please use '?' mark in placeholder e.g. {{? <fhir-path-expression>}}  or correct your expression", Some(fhirPathExpression))

    if(!isArray && result.length > 1)
      throw FhirExpressionException(s"FHIR path expression returns multiple results although value is not marked as array! Please use '*' mark in placeholder e.g. {{* <fhir-path-expression>}} or correct your expression", Some(fhirPathExpression))

    if(isArray)
      JArray(result.toList)
    else
      result.headOption.getOrElse(JNull)
  }
}

