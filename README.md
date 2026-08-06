# onFHIR reusable libraries

Reusable Scala libraries for working with HL7 FHIR resources, queries,
validation, configuration, FHIRPath, templates, and HTTP clients. The library
family is transport-neutral and has no Akka or Pekko dependency.

The first independently versioned release is `4.0.0`. Existing Maven artifact
IDs and `io.onfhir.*` package names are preserved.

## Modules

| Module | Purpose |
|---|---|
| [onfhir-common](onfhir-common/README.md) | neutral FHIR models, configuration values, interfaces, and shared utilities |
| [onfhir-client](onfhir-client/README.md) | JDK HTTP FHIR client and request builders |
| [onfhir-path](onfhir-path/README.md) | standalone FHIRPath parsing and evaluation |
| [onfhir-query](onfhir-query/README.md) | FHIR/x-fhir-query parsing and in-memory search evaluation |
| [onfhir-config](onfhir-config/README.md) | loading and interpreting FHIR infrastructure configuration |
| [onfhir-expression](onfhir-expression/README.md) | expression model and language-handler dispatch |
| [onfhir-validation](onfhir-validation/README.md) | profile, terminology, reference, and invariant validation |
| [onfhir-template-engine](onfhir-template-engine/README.md) | declarative generation of FHIR or JSON content from JSON templates with FHIRPath placeholders |
| [onfhir-r4](onfhir-r4/README.md) | reusable FHIR R4 foundation-resource parsers |
| [onfhir-r5](onfhir-r5/README.md) | reusable FHIR R5 parser facade, defaults, and compatibility tests |
| [onfhir-stu3](onfhir-stu3/README.md) | reusable FHIR STU3 foundation-resource parsers, layered on the R4 parsers |
| [onfhir-definitions-r4](onfhir-definitions-r4/README.md) | packaged HL7 FHIR R4 (4.0.1) standard definitions and base CapabilityStatement (resources only, no Scala suffix) |
| [onfhir-definitions-r5](onfhir-definitions-r5/README.md) | packaged HL7 FHIR R5 (5.0.0) standard definitions and base CapabilityStatement (resources only, no Scala suffix) |
| [onfhir-definitions-stu3](onfhir-definitions-stu3/README.md) | packaged HL7 FHIR STU3 (3.0.2) standard definitions and base CapabilityStatement (resources only, no Scala suffix) |

## Maven

Use only the modules needed by your application. For example:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-client_2.13</artifactId>
  <version>4.0.0</version>
</dependency>
```

Applications using several modules can import the optional BOM:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.onfhir</groupId>
      <artifactId>onfhir-libs-bom</artifactId>
      <version>4.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Logging

Modules log through `slf4j-api` and do not include or configure an SLF4J
provider. The consuming application selects one provider, such as Logback,
Log4j 2, or `java.util.logging`, at deployment time. Applications that do not
install a provider receive SLF4J's no-operation fallback behavior.

## Build and verification

JDK 17 and Maven are used for the verified build; emitted bytecode targets
Java 11.

```shell
mvn test
pwsh -File scripts/check-forbidden-imports.ps1
pwsh -File scripts/check-library-dependency-licenses.ps1
```

The `release` profile attaches source and Scaladoc JARs and signs staged
artifacts. Activating it does not publish to Maven Central.

## Compatibility and license

Version 4.0.0 intentionally contains major API changes and module moves from
the 3.x monorepo line. See the
[migration plan](docs/plans/library-server-split-plan-v2.md) and
[MiMa reconciliation](docs/compatibility/mima-3.3-reconciliation.md).

Copyright 2019-2026 SRDC Corp. and contributors. Licensed under the
[Apache License 2.0](LICENSE).
