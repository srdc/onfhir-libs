# onfhir-expression

`onfhir-expression` provides a language-neutral facade for validating and
evaluating expressions used in FHIR-based applications.

The module is inspired by the HL7 FHIR
[`Expression`](https://hl7.org/fhir/metadatatypes.html#Expression) datatype,
which identifies an expression language by media type and leaves the
evaluation context and interpretation of the result to the consumer.

This module is not an expression engine. It defines the common model and
extension contract through which independently packaged language engines can
be used behind one API.

Maven coordinate: `io.onfhir:onfhir-expression_2.13`.

## Responsibilities

- `FhirExpression` describes an expression and its language.
- `IFhirExpressionLanguageHandler` is the extension contract implemented by a
  language engine or adapter.
- `FhirExpressionEvaluator` selects a registered handler using the expression
  language and delegates validation or evaluation to it.
- `FhirExpressionException` is the common exception type for dispatch,
  validation, and evaluation failures.

Concrete languages remain separate dependencies. An application includes and
registers only the handlers it needs.

## Module relationships

| Module | Responsibility | Facade integration |
| --- | --- | --- |
| `onfhir-expression` | Common model, handler contract, and dispatch | Provides the facade |
| [`onfhir-template-engine`](../onfhir-template-engine/README.md) | onFHIR JSON template language | Provides `FhirTemplateExpressionHandler` |
| [`onfhir-path`](../onfhir-path/README.md) | FHIRPath parsing and evaluation | Direct API; no expression handler is currently provided |
| [`onfhir-query`](../onfhir-query/README.md) | FHIR search and x-fhir-query parsing/resolution | Direct API; no expression handler is currently provided |

`onfhir-path` and `onfhir-query` contain capabilities from which FHIRPath and
x-fhir-query handlers could be built. Such adapters are intentionally not part
of the current API because there is no present use case requiring them.

## Expression model

`FhirExpression` follows the concepts of the FHIR `Expression` datatype but is
an internal Scala model rather than its exact FHIR JSON representation.

| Field | Meaning |
| --- | --- |
| `name` | Required name used by the application to identify the expression |
| `language` | Required language media type; it is also the handler dispatch key |
| `description` | Optional human-readable explanation |
| `expression` | Optional inline textual expression |
| `reference` | Optional URI identifying externally stored expression content |
| `value` | Optional onFHIR extension carrying structured JSON content, such as a JSON template |

The facade does not resolve `reference` URIs or enforce every invariant of the
FHIR datatype. Each language handler decides which content fields it accepts,
validates their combinations, and defines the result semantics. For example,
the template engine can consume structured content from `value`.

## Registering and using handlers

Create the facade with the language handlers available to the application:

```scala
import io.onfhir.expression.{FhirExpression, FhirExpressionEvaluator}
import org.json4s.JValue

import scala.concurrent.ExecutionContext.Implicits.global

val evaluator = new FhirExpressionEvaluator(Seq(myLanguageHandler))

val expression = FhirExpression(
  name = "eligible",
  language = myLanguageHandler.languageSupported,
  expression = Some("status = 'active'")
)

evaluator.validateExpression(expression)

val result = evaluator.evaluateExpression(
  expression,
  contextParams = Map.empty[String, JValue],
  input = patient
)
```

`evaluateExpression` returns a `Future[JValue]`. Use `satisfies` when the
selected language supports evaluating an expression as a boolean condition:

```scala
val applicable = evaluator.satisfies(
  expression,
  contextParams = Map.empty[String, JValue],
  input = patient
)
```

Evaluation is asynchronous and requires an implicit Scala `ExecutionContext`.

## Implementing a language handler

A language implementation integrates with the facade by implementing
`IFhirExpressionLanguageHandler`:

```scala
import io.onfhir.expression.{
  FhirExpression,
  FhirExpressionException,
  IFhirExpressionLanguageHandler
}
import org.json4s.{JNothing, JValue}

import scala.concurrent.{ExecutionContext, Future}

final class MyExpressionHandler extends IFhirExpressionLanguageHandler {
  override val languageSupported: String = "application/x-my-expression"

  override def validateExpression(expression: FhirExpression): Unit = {
    if (expression.expression.isEmpty)
      throw FhirExpressionException("Missing expression content")
  }

  override def evaluateExpression(
      expression: FhirExpression,
      contextParams: Map[String, JValue],
      input: JValue = JNothing
  )(implicit ec: ExecutionContext): Future[JValue] = {
    validateExpression(expression)
    Future {
      // Parse and evaluate the language-specific content.
      input
    }
  }

  override def satisfies(
      expression: FhirExpression,
      contextParams: Map[String, JValue],
      input: JValue = JNothing
  )(implicit ec: ExecutionContext): Future[Boolean] = {
    validateExpression(expression)
    Future {
      // Evaluate the expression using boolean result semantics.
      true
    }
  }
}
```

Use a stable media type as `languageSupported`. Handler lookup uses exact
string equality, and the first registered handler with a matching value is
selected, so an application should not register duplicate handlers for the
same language.

`FhirExpressionEvaluator` implements the same contract, so it can be supplied
where one handler is expected. Its own `languageSupported` value is `*`, which
is a placeholder rather than a wildcard pattern: because dispatch uses exact
equality, a facade registered inside another facade is only ever selected for
the literal language `*`. Compose the handler sequences instead of nesting
facades.

The examples above are executable in
[`FhirExpressionReadmeExampleTest`](src/test/scala/io/onfhir/expression/FhirExpressionReadmeExampleTest.scala),
and the dispatch contract is covered by
[`FhirExpressionEvaluatorTest`](src/test/scala/io/onfhir/expression/FhirExpressionEvaluatorTest.scala).

## Current implementation

The currently supplied implementation is
`FhirTemplateExpressionHandler` from
`io.onfhir:onfhir-template-engine_2.13`. Its language is
`application/fhir-template+json`; it generates JSON content from onFHIR
templates containing FHIRPath placeholders.

The template handler supports `validateExpression` and `evaluateExpression`.
Templates are value-generating expressions, so the handler rejects
`satisfies`. Its `validateExpression` checks only that the expression carries
template content; placeholder syntax and the embedded FHIRPath expressions are
validated during rendering. See the
[`onfhir-template-engine` README](../onfhir-template-engine/README.md) for its
syntax and executable examples.

## Dispatch and error behavior

- Handler selection is an exact match between `FhirExpression.language` and
  `IFhirExpressionLanguageHandler.languageSupported`. Matching is
  case-sensitive, so `text/FHIRPath` does not select a handler registered for
  `text/fhirpath`.
- An unregistered language causes `FhirExpressionException`. The
  value-returning methods report it through the returned `Future`, so
  `recover` alone is enough; only the `Unit`-returning `validateExpression`
  throws synchronously.
- The selected handler owns language-specific syntax validation.
- The selected handler determines whether value evaluation, boolean
  evaluation, or both are supported.
- The facade does not retrieve content referenced by `FhirExpression.reference`.
- Inputs, context parameters, and generated values are represented as json4s
  `JValue` values.
