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
| [onfhir-template-engine](onfhir-template-engine/README.md) | JSON/FHIR template rendering with FHIRPath placeholders |
| [onfhir-r4](onfhir-r4/README.md) | reusable FHIR R4 foundation-resource parsers |

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
