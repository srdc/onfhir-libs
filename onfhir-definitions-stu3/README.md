# onfhir-definitions-stu3

`onfhir-definitions-stu3` is a resources-only artifact that packages the
official HL7 FHIR STU3 (3.0.2) standard definitions bundle and the base STU3
`CapabilityStatement`. It contains no code. Its purpose is to be the single
shared source of the STU3 standard package for onFHIR libraries, tests, and
downstream applications, instead of each repository embedding its own copy.

Maven coordinate: `io.onfhir:onfhir-definitions-stu3`.

See [`onfhir-definitions-r4`](../onfhir-definitions-r4/README.md) and
[`onfhir-definitions-r5`](../onfhir-definitions-r5/README.md) for the other
releases. One artifact is published per FHIR release; they can share a
classpath because every packaged file name carries its release.

## No Scala version suffix

Unlike the other onFHIR library artifacts, this artifact ID carries no
`_2.13` suffix. The Scala-suffix convention identifies artifacts whose binary
content is bound to a Scala binary version. This artifact contains only JSON
and ZIP resources, so it is usable from any Scala or Java build without
duplication per Scala version.

## Contents

| Classpath resource | Description |
| --- | --- |
| `definitions-stu3.json.zip` | FHIR STU3 `definitions.json.zip` as published by HL7 (profiles, extensions, value sets, code systems, search parameters) |
| `conformance-statement-stu3.json` | base STU3 `CapabilityStatement` used as the starting point for an application capability statement |
| `onfhir-definitions-stu3.properties` | packaged FHIR version, upstream source URL, content license, and file inventory |

### Why these file names

The Repofyr server module ships these files under the generic names
`definitions.json.zip` and `conformance-statement.json`. They are renamed here
so that the release-specific default resolution in `onfhir-config` finds them
and so that several definitions artifacts can share a classpath.

`BaseConfigReader` has no dedicated STU3 branch, so it falls through to

```scala
FHIRUtil.mergeFilePath(DEFAULT_ROOT_FOLDER,
  s"definitions-${fhirVersion.toLowerCase}${FOUNDATION_RESOURCES_FILE_SUFFIX}.zip")
```

which for `fhirVersion = "STU3"` yields `definitions-stu3.json.zip` - note the
`.json` element, which comes from `FOUNDATION_RESOURCES_FILE_SUFFIX` and is
present in the R4 and R5 names too. `FSConfigReader.readCapabilityStatement()`
falls through the same way to `conformance-statement-stu3.json`. A configurator
declaring `fhirVersion = "DSTU3"` would look for `definitions-dstu3.json.zip`
instead; Repofyr's `FhirSTU3Configurator` declares `"STU3"`.

Server persistence configuration (`db-index-conf.json` in the server module) is
deliberately not included; it is server-specific and belongs to the server
repository.

### Which STU3 patch release this is

The definitions bundle is STU3 **3.0.2**: 877 resources in `valuesets.json`
carry `"version": "3.0.2"` and the content is dated 2019-10-24. The base
CapabilityStatement inside the same HL7 package nevertheless declares
`"fhirVersion": "3.0.1"`. That inconsistency is upstream and is left as
published; both values are recorded in
`onfhir-definitions-stu3.properties` (`fhir.version` and
`capability.statement.declared.version`).

## Terminology bundles

Unlike the R5 package, the STU3 ZIP does ship `v3-codesystems.json` and
`v2-tables.json` alongside `valuesets.json`, so the default
`IFhirVersionConfigurator.VALUESET_AND_CODESYSTEM_BUNDLE_FILES` list works
without narrowing. No configurator override is needed for terminology.

## Usage

Pair it with [`onfhir-stu3`](../onfhir-stu3/README.md), which supplies the
STU3 foundation-resource parsers:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-definitions-stu3</artifactId>
  <version>4.0.0</version>
  <scope>test</scope>
</dependency>
```

```scala
import io.onfhir.config.FSConfigReader

// Resolves definitions-stu3.json.zip and conformance-statement-stu3.json
// from the classpath, with no explicit paths.
val configReader = new FSConfigReader(fhirVersion = "STU3")
```

Applications that ship their own definitions package can keep doing so by
passing explicit paths to `FSConfigReader` and omitting this dependency.

## Versioning

The artifact version tracks the onfhir-libs release line (currently `4.0.0`)
so that it stays in lockstep with the reactor. The FHIR package version it
carries is `3.0.2` and is recorded in `onfhir-definitions-stu3.properties`, not
in the Maven version. A future FHIR release gets its own artifact rather than a
version bump here.

## License

The onFHIR packaging around this content is licensed under the
[Apache License 2.0](../LICENSE), like the rest of this repository.

The packaged FHIR content itself is HL7 FHIR specification material. HL7
publishes the FHIR specification content under
[Creative Commons CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/).
See the repository `NOTICE` file. FHIR(R) is a registered trademark of HL7 and
its use does not constitute an endorsement of this artifact by HL7.
