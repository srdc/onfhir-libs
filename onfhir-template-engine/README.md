# onfhir-template-engine

`onfhir-template-engine` generates FHIR resources — or any JSON document — from
a template that is itself JSON and whose placeholders are FHIRPath expressions.
You write the resource you want to produce, put FHIRPath expressions where data
should flow in from the incoming resource, and the engine renders the result.
There is no mapping code to write, compile, or deploy.

Two properties make it usable as a mapping layer rather than a string
substituter:

- **Templates are data.** A template is a JSON value, so it can be stored in a
  FHIR resource, versioned, reviewed, shipped per deployment, and changed
  without rebuilding the application.
- **Placeholders carry FHIR cardinality.** Markers declare `0..1`, `1..1`,
  `0..*`, and `1..*`, arrays are emitted as arrays even for a single result, and
  elements whose expressions produce nothing are pruned. The output is
  cardinality-shaped FHIR rather than a document full of nulls and empty
  objects that a later step has to clean up.

The engine is FHIR-release neutral. It needs no `StructureDefinition`, no
definitions package, and no server: it depends only on the onFHIR Expression
and FHIRPath libraries. The same template renders against R4, R5, or a
non-FHIR JSON input (`isSourceContentFhir = false`).

The template language MIME type is `application/fhir-template+json`. The engine
is used in production for FHIR mapping in ignifyr.

## Typical uses

- **Notification and subscription payloads.** Turn a triggering resource into
  the `Communication`, `Task`, or `Bundle` that gets delivered, resolving
  recipients from context resources.
- **Mapping pipelines.** Map incoming events or source records to FHIR
  resources, with one template per target resource type. Handlers are
  serializable, so a template renderer can be shipped to distributed workers.
- **Care-plan and decision-support content.** Produce `CarePlan`,
  `ServiceRequest`, or `Task` content from a triggering resource plus named
  context values, with optional sections that appear only when the data is
  there.
- **Repeating structures without code.** A section repeats a sub-template once
  per item of a FHIRPath collection, so participant lists, payload entries, and
  extension arrays are declarative.
- **Test and seed data.** Generate consistent resources from a small input.

## Compared with the alternatives

| Approach | Trade-off |
|---|---|
| FHIR Mapping Language / `StructureMap` | The standard, and more expressive for structural transformation. It needs a mapping engine, modelled source and target structures, and authoring expertise. |
| Generic text templating (mustache, Liquid) | No FHIRPath and no notion of FHIR cardinality. Templates render text, so JSON validity, type fidelity, and empty-element cleanup are the caller's problem. |
| Hand-written mapping code | Full control, and the right choice for genuinely algorithmic transformation. Mapping intent is spread across code, and every change is a rebuild and redeploy. |
| `onfhir-template-engine` | FHIRPath is the only expression language, the template is data, and cardinality and pruning are handled. Scope is deliberately narrow: one-way generation from a single input plus named context values, with no structural recursion over the source. |

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

## A worked example

An `Observation` event and a `CareTeam` context value produce a `Communication`
that notifies every care team member.

Template:

```json
{
  "resourceType": "Communication",
  "status": "completed",
  "subject": "{{ Observation.subject }}",
  "sent": "{{ now() }}",
  "recipient": {
    "{{#member}}": "{{ %careTeam.participant.member }}",
    "{{*}}": {
      "reference": "{{ %member.reference }}",
      "display": "{{? %member.display }}"
    }
  },
  "payload": [
    {
      "contentString": "Patient {{ Observation.subject.display }} has a potassium value of {{ Observation.valueQuantity.value }} mmol/L."
    },
    {
      "contentReference": { "reference": "Observation/{{ Observation.id }}" }
    }
  ]
}
```

Rendered against an `Observation` for `Patient/f001` and a two-member
`CareTeam` supplied as `careTeam`:

```json
{
  "resourceType": "Communication",
  "status": "completed",
  "subject": {
    "reference": "Patient/f001",
    "display": "P. van de Heuvel"
  },
  "sent": "2026-08-05T17:08:17.825+03:00",
  "recipient": [
    {
      "reference": "Practitioner/pr1",
      "display": "Dorothy Dietition"
    },
    {
      "reference": "Patient/example"
    }
  ],
  "payload": [
    {
      "contentString": "Patient P. van de Heuvel has a potassium value of 6.3 mmol/L."
    },
    {
      "contentReference": {
        "reference": "Observation/obs-00001"
      }
    }
  ]
}
```

The complete element `Observation.subject` is copied with its JSON structure
intact, the section produced one `recipient` per care team member, and the
second member's absent `display` was pruned rather than emitted as `null`. This
example is executable in
[`FhirTemplateReadmeExampleTest`](src/test/scala/io/onfhir/template/FhirTemplateReadmeExampleTest.scala).

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
quantity results raise `FhirExpressionException`, as do cardinality markers,
which are not supported in this position. Unlike complete-value placeholders,
embedded results are always converted to text and inserted literally, so a
resolved value containing `$` or `\` needs no escaping in the template.

### Sections

Sections create repeated array items or an optional structured value from a
FHIRPath collection. A section is a JSON object with exactly two fields, in
either order:

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

Sections nest: a sub-template may contain further sections, each binding its
own variable. A section placed inside a JSON array contributes its items to the
surrounding array.

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
become empty are also removed; a template whose every field was removed renders
as `JNull`. When a template inside an existing JSON array returns an array, its
items are flattened into the surrounding array.

Invalid placeholders, malformed sections, cardinality mismatches, and FHIRPath
evaluation errors are reported as `FhirExpressionException`, with the failing
expression where available.

## Reuse and thread safety

A handler holds an immutable FHIRPath evaluator and no per-render state, so a
single instance can be created once and used concurrently for any number of
renders; a failed render does not affect later ones. The handler is
`Serializable`, so it can be constructed on a driver and used inside
distributed tasks.

`validateExpression` checks only that the expression carries template content.
Placeholder syntax and the embedded FHIRPath expressions are validated during
rendering, not ahead of it.

## Limitations

- Template expressions cannot be used for boolean applicability checks;
  `satisfies` always fails.
- The handler evaluates inline template content and does not load templates
  from `FhirExpression.reference` URLs.
- A template supplied as a string in `FhirExpression.expression` instead of a
  parsed JSON value in `value` is rendered as a string, producing a JSON string
  rather than a JSON document.
- Embedded string placeholders cannot render complex JSON values or
  collections; use a complete-value placeholder or section instead.
- Rendering is one-way and reads a single input resource plus named context
  values. There is no structural recursion over the source and no reverse
  mapping.

## Tests and examples

The README examples are executable in
[`FhirTemplateReadmeExampleTest`](src/test/scala/io/onfhir/template/FhirTemplateReadmeExampleTest.scala).
Placeholder cardinality and JSON type fidelity are covered by
[`FhirTemplatePlaceholderTest`](src/test/scala/io/onfhir/template/FhirTemplatePlaceholderTest.scala),
sections by
[`FhirTemplateSectionTest`](src/test/scala/io/onfhir/template/FhirTemplateSectionTest.scala),
literal substitution of resolved values by
[`FhirTemplateEscapingTest`](src/test/scala/io/onfhir/template/FhirTemplateEscapingTest.scala),
and validation, context precedence, and output cleanup by
[`FhirTemplateHandlerContractTest`](src/test/scala/io/onfhir/template/FhirTemplateHandlerContractTest.scala).
End-to-end templates live in
[`FhirTemplateExpressionHandlerTest`](src/test/scala/io/onfhir/template/FhirTemplateExpressionHandlerTest.scala)
with their content under `src/test/resources/templates`.

Run the module and its prerequisites with:

```shell
mvn -pl onfhir-template-engine -am test
```
