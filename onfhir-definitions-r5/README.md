# onfhir-definitions-r5

`onfhir-definitions-r5` is a resources-only artifact that packages the official
HL7 FHIR R5 (5.0.0) standard definitions bundle and the base R5
`CapabilityStatement`. It contains no code. Its purpose is to be the single
shared source of the R5 standard package for onFHIR libraries, tests, and
downstream applications, instead of each repository embedding its own copy.

Maven coordinate: `io.onfhir:onfhir-definitions-r5`.

See [`onfhir-definitions-r4`](../onfhir-definitions-r4/README.md) for the R4
equivalent. One artifact is published per FHIR release; the two can sit on the
same classpath because every packaged file name carries its release.

## No Scala version suffix

Unlike the other onFHIR library artifacts, this artifact ID carries no
`_2.13` suffix. The Scala-suffix convention identifies artifacts whose binary
content is bound to a Scala binary version. This artifact contains only JSON
and ZIP resources, so it is usable from any Scala or Java build without
duplication per Scala version.

## Contents

| Classpath resource | Description |
| --- | --- |
| `definitions-r5.json.zip` | FHIR R5 `definitions.json.zip` as published by HL7 (profiles, extensions, value sets, search parameters) |
| `conformance-statement-r5.json` | base R5 `CapabilityStatement` used as the starting point for an application capability statement |
| `onfhir-definitions-r5.properties` | packaged FHIR version, upstream source URL, content license, and file inventory |

Both resources sit at the root of the classpath, which is exactly where
`onfhir-config`'s default resolution looks for them: `BaseConfigReader`
resolves the standard bundle to `DEFAULT_RESOURCE_PATHS.BASE_DEFINITONS_R5`
and `FSConfigReader.readCapabilityStatement()` resolves to
`DEFAULT_RESOURCE_PATHS.CONFORMANCE_PATH_R5` for version `"R5"` or `"5.0.0"`,
both of which evaluate to those names when `io.onfhir.api.DEFAULT_ROOT_FOLDER`
is left unset. Putting this artifact on the classpath therefore makes
`new FSConfigReader("R5")` resolve its inputs with no explicit paths.

Server persistence configuration such as `db-index-conf-r5.json` is
deliberately not included; it is server-specific and belongs to the server
repository.

## Important: the R5 package has no v2/v3 terminology bundles

The R5 ZIP contains ten entries and, unlike the R4 ZIP, ships **no
`v3-codesystems.json` and no `v2-tables.json`**. In R5 those HL7 v2 and v3 code
systems moved out of the core specification package and into the separate
terminology (THO) package.

This matters because `IFhirVersionConfigurator` declares:

```scala
protected val VALUESET_AND_CODESYSTEM_BUNDLE_FILES: Seq[String] =
  Seq("valuesets.json", "v3-codesystems.json", "v2-tables.json")
```

and `BaseFhirConfigurator.initializePlatform` reads every entry of that list
through `IFhirConfigReader.readStandardBundleFile`, which throws
`InitializationException` when a named bundle is absent from the ZIP. A
configurator driven against this package must therefore narrow the list:

```scala
class R5Configurator extends BaseFhirConfigurator {
  override val fhirVersion: String = "R5"

  // The R5 core package ships only valuesets.json; v2/v3 code systems are in
  // the separate HL7 terminology package.
  override protected val VALUESET_AND_CODESYSTEM_BUNDLE_FILES: Seq[String] =
    Seq("valuesets.json")

  override def getFoundationResourceParser(complexTypes: Set[String],
                                           primitiveTypes: Set[String],
                                           capabilityDefaults: FhirCapabilityDefaults) = ???
}
```

Applications that need the v2/v3 code systems for R5 terminology validation
must supply them separately, for example through `FSConfigReader`'s
`codeSystemsPath`.

## R5 parser companion

[`onfhir-r5`](../onfhir-r5/README.md) supplies the release-specific `R5Parser`.
It currently extends `R4Parser` because the infrastructure-resource shapes
onFHIR consumes remain compatible, while owning the R5 default datatype sets
and the extension point for future R5 differences.

`onfhir-r5` consumes this definitions artifact in test scope. Its
`R5StandardPackageParsingTest` and `R5StandardValidationTest` parse and validate
against the full 5.0.0 package, including the terminology-bundle narrowing
shown above. This artifact itself remains parser-neutral and resources-only.

## Usage

Typical use is test scope, where a suite needs the real standard package:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-definitions-r5</artifactId>
  <version>4.0.0</version>
  <scope>test</scope>
</dependency>
```

```scala
import io.onfhir.config.FSConfigReader

// Resolves definitions-r5.json.zip and conformance-statement-r5.json
// from the classpath, with no explicit paths.
val configReader = new FSConfigReader(fhirVersion = "R5")
```

Applications that ship their own definitions package can keep doing so by
passing explicit paths to `FSConfigReader` and omitting this dependency.

## Versioning

The artifact version tracks the onfhir-libs release line (currently `4.0.0`)
so that it stays in lockstep with the reactor. The FHIR package version it
carries is `5.0.0` and is recorded in `onfhir-definitions-r5.properties`, not
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
