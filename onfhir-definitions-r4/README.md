# onfhir-definitions-r4

`onfhir-definitions-r4` is a resources-only artifact that packages the
official HL7 FHIR R4 (4.0.1) standard definitions bundle and the base R4
`CapabilityStatement`. It contains no code. Its purpose is to be the single
shared source of the R4 standard package for onFHIR libraries, tests, and
downstream applications, instead of each repository embedding its own copy.

Maven coordinate: `io.onfhir:onfhir-definitions-r4`.

## No Scala version suffix

Unlike the other onFHIR library artifacts, this artifact ID carries no
`_2.13` suffix. The Scala-suffix convention identifies artifacts whose binary
content is bound to a Scala binary version. This artifact contains only JSON
and ZIP resources, so it is usable from any Scala or Java build without
duplication per Scala version.

## Contents

| Classpath resource | Description |
| --- | --- |
| `definitions-r4.json.zip` | FHIR R4 `definitions.json.zip` as published by HL7 (profiles, extensions, value sets, code systems, search parameters) |
| `conformance-statement-r4.json` | base R4 `CapabilityStatement` used as the starting point for an application capability statement |
| `onfhir-definitions-r4.properties` | packaged FHIR version, upstream source URL, content license, and file inventory |

Both resources sit at the root of the classpath, which is exactly where
`onfhir-config`'s default resolution looks for them: `BaseConfigReader`
resolves the standard bundle to `DEFAULT_RESOURCE_PATHS.BASE_DEFINITONS_R4`
and `FSConfigReader.readCapabilityStatement()` resolves to
`DEFAULT_RESOURCE_PATHS.CONFORMANCE_PATH_R4`, both of which evaluate to those
names when `io.onfhir.api.DEFAULT_ROOT_FOLDER` is left unset. Putting this
artifact on the classpath therefore makes `new FSConfigReader("R4")` work with
no explicit paths.

The zip contains the standard bundle files the release-neutral configurator
reads: `profiles-resources.json`, `profiles-types.json`,
`profiles-others.json`, `extension-definitions.json`, `valuesets.json`,
`v3-codesystems.json`, `v2-tables.json`, and `search-parameters.json`.

Server persistence configuration such as `db-index-conf-r4.json` is
deliberately not included; it is server-specific and belongs to the server
repository.

## Usage

Typical use is test scope, where a suite needs the real standard package:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-definitions-r4</artifactId>
  <version>4.0.0</version>
  <scope>test</scope>
</dependency>
```

```scala
import io.onfhir.config.FSConfigReader

// Resolves definitions-r4.json.zip and conformance-statement-r4.json
// from the classpath, with no explicit paths.
val configReader = new FSConfigReader(fhirVersion = "R4")
```

Applications that ship their own definitions package can keep doing so by
passing explicit paths to `FSConfigReader` and omitting this dependency.

## Versioning

The artifact version tracks the onfhir-libs release line (currently `4.0.0`)
so that it stays in lockstep with the reactor. The FHIR package version it
carries is `4.0.1` and is recorded in `onfhir-definitions-r4.properties`, not in
the Maven version. A future FHIR release gets its own artifact, such as the
existing [`onfhir-definitions-r5`](../onfhir-definitions-r5/README.md), rather
than a version bump here. Every packaged file name carries its release, so
several definitions artifacts can sit on one classpath without colliding.

## License

The onFHIR packaging around this content is licensed under the
[Apache License 2.0](../LICENSE), like the rest of this repository.

The packaged FHIR content itself is HL7 FHIR specification material. HL7
publishes the FHIR specification content under
[Creative Commons CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/).
See the repository `NOTICE` file. FHIR(R) is a registered trademark of HL7 and
its use does not constitute an endorsement of this artifact by HL7.
