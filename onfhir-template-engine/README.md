# onfhir-template-engine

`onfhir-template-engine` renders JSON templates containing FHIRPath
expressions. It can create FHIR resources or ordinary JSON documents from an
input resource plus named context values. The template language MIME type is
`application/fhir-template+json`.

The module is standalone: it depends on the onFHIR Expression and FHIRPath
libraries.

## Dependency

Because the public API is implemented in Scala, the artifact carries the
Scala 2.13 binary-version suffix:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-template-engine_2.13</artifactId>
  <version>4.0.0</version>
</dependency>
```

## Rendering a template

The public entry point is `FhirTemplateExpressionHandler`. Rendering is
asynchronous and requires a Scala `ExecutionContext`.

```scala
import io.onfhir.expression.FhirExpression
import io.onfhir.template.FhirTemplateExpressionHandler
import org.json4s.jackson.JsonMethods.parse

import scala.concurrent.ExecutionContext.Implicits.global

val handler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)
val patient = parse("""{"resourceType":"Patient","id":"p1"}""")
val template = FhirExpression(
  "patient-summary",
  handler.languageSupported,
  value = Some(parse("""{"id":"{{ Patient.id }}"}"""))
)

val rendered = handler.evaluateExpression(template, Map.empty, patient)
// Future[JValue] containing {"id":"p1"}
```

The expression's `value` contains the parsed JSON template. The third argument
to `evaluateExpression` is the input against which ordinary FHIRPath
expressions are evaluated. The context map supplies variables referenced with
FHIRPath's `%variable` syntax.

## Template language

Placeholders use double braces and contain FHIRPath expressions. The engine
supports three forms:

1. a placeholder that is the complete value of a JSON field;
2. one or more placeholders embedded in a string;
3. a section that builds an optional object or repeats a sub-template.

### Complete-value placeholders

When the entire JSON string is one placeholder, the FHIRPath result becomes
the field's JSON value. Objects, arrays, numbers, booleans, and strings retain
their JSON types.

```json
{
  "resourceType": "Communication",
  "subject": "{{? Observation.subject }}",
  "sent": "{{ now() }}",
  "reasonCode": "{{* Observation.category }}"
}
```

An optional cardinality marker may follow the opening braces:

| Form | Meaning |
|---|---|
| `{{ expression }}` | Exactly one result is required. Empty or multiple results fail. |
| `{{? expression }}` | Zero or one result. An empty result removes the field. |
| `{{* expression }}` | Zero or more results, always emitted as a JSON array. |
| `{{+ expression }}` | One or more results, always emitted as a JSON array; empty results fail. |

Include a space between a marker and its FHIRPath expression, as shown above.
Use `*` for FHIR elements with cardinality `0..*` and `+` when at least one
array item is required.

### Placeholders inside strings

A placeholder can form part of a larger string:

```json
{
  "contentString": "Patient {{ Patient.name.first().text }} has a new result.",
  "contentReference": {
    "reference": "Observation/{{ Observation.id }}"
  }
}
```

Each embedded expression must return exactly one primitive value: a string,
number, boolean, date/time, or time. Empty, multiple, object, array, or
quantity results raise `FhirExpressionException`. Unlike complete-value
placeholders, embedded results are always converted to text.

### Sections

Sections create repeated array items or an optional structured value from a
FHIRPath collection. A section is a JSON object with exactly two fields:

- `{{#name}}` declares the section variable; its value is a complete
  placeholder that selects the collection;
- `{{*}}`, `{{+}}`, or `{{?}}` supplies the sub-template and its cardinality.

For example, this renders one `recipient` entry for each CareTeam member:

```json
{
  "resourceType": "Communication",
  "recipient": {
    "{{#member}}": "{{ %careTeamOfPatient.participant.member }}",
    "{{*}}": {
      "reference": "{{ %member.reference }}",
      "display": "{{? %member.display }}",
      "extension": [
        {
          "url": "https://example.org/fhir/StructureDefinition/recipient-status",
          "valueCode": "sent"
        }
      ]
    }
  }
}
```

For `{{*}}` and `{{+}}`, the engine evaluates the sub-template once per
selected item and exposes that item as `%member`. It also exposes the one-based
`%sectionIndex` variable. `{{*}}` permits an empty collection; `{{+}}` fails
when the collection is empty.

With `{{?}}`, the sub-template is evaluated once when the section expression
has results and omitted otherwise. The declared variable contains the single
result directly or all results as an array.

## Context and services

Context values can be supplied at two levels:

- `staticContextParams` on `FhirTemplateExpressionHandler` are available to
  every render;
- the map passed to `evaluateExpression` is specific to that render and
  overrides a static value with the same name.

```scala
import org.json4s.JsonAST.JString

val handler = new FhirTemplateExpressionHandler(
  staticContextParams = Map("tenant" -> JString("hospital-a")),
  isSourceContentFhir = true
)

val perRenderContext = Map("subjectRef" -> JString("Patient/123"))
```

The constructor also accepts:

- `functionLibraryFactories` for custom FHIRPath function libraries;
- `terminologyService` for terminology functions;
- `identityService` for identity-related functions;
- `isSourceContentFhir`, which tells FHIRPath whether the input uses FHIR JSON
  semantics.

## Output cleanup and errors

After rendering, null values are removed recursively. Objects and arrays that
become empty are also removed. When a template inside an existing JSON array
returns an array, its items are flattened into the surrounding array.

Invalid placeholders, cardinality mismatches, and FHIRPath evaluation errors
are reported as `FhirExpressionException`, with the failing expression where
available.

## Limitations

- Template expressions cannot be used for boolean applicability checks;
  `satisfies` always fails.
- The handler evaluates inline template content and does not load templates
  from `FhirExpression.reference` URLs.
- Embedded string placeholders cannot render complex JSON values or
  collections; use a complete-value placeholder or section instead.

## Tests and examples

The minimal Scala example is executable in
[`FhirTemplateReadmeExampleTest`](src/test/scala/io/onfhir/template/FhirTemplateReadmeExampleTest.scala).
The full placeholder and section behavior is covered by
[`FhirTemplateExpressionHandlerTest`](src/test/scala/io/onfhir/template/FhirTemplateExpressionHandlerTest.scala)
and its templates under `src/test/resources/templates`.

Run the module and its prerequisites with:

```shell
mvn -pl onfhir-template-engine -am test
```
