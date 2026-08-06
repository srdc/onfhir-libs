# Known Limitations To Convert Into GitHub Issues After Publishing

These are deliberate, documented gaps in the 4.0.0 libraries. Each entry
should become a GitHub issue once `srdc/onfhir-libs` is public, so they are
tracked openly instead of living only in code comments. Source comments
reference this file.

## onfhir-validation

1. Intensional ValueSet definitions without supplied CodeSystem content
   - `TerminologyParser.parseValueSet` omits a ValueSet that yields no
     locally resolvable codes (e.g. "all of SNOMED CT"). Such bindings must
     be validated through an external terminology service; locally they are
     reported as unsupported (warning), not as errors.
   - See the module README "Limitations" section.

2. XHTML narrative content is not validated
   - `FhirContentValidator.validatePrimitive` accepts any `xhtml` value, and
     `AbstractStructureDefinitionParser.parseConstraint` skips the
     `htmlChecks()` invariant because it is not a FHIRPath expression.
     Implementing an html-check function would close both gaps together.

3. Lenient lexical validation for `uri`, `url`, and `base64Binary`
   - `uri` and `url` are only required to be non-empty strings because
     FHIR's own definitions do not conform to strict URI syntax;
     `base64Binary` content is accepted without decoding. Revisit whether a
     configurable strict mode is worth offering.

4. Slicing discriminator path normalization is heuristic
   - `FhirContentValidator` strips slice-name segments (e.g.
     `component:alpha.code`) from discriminator paths before FHIRPath
     evaluation and special-cases `extension(...)`; other function-style
     discriminator paths are not normalized.

## onfhir-path

5. An undefined FHIRPath environment variable resolves to empty, not an error
   - The FHIRPath spec treats an undefined `%name` as an error, so a typo in
     an expression is silently empty rather than reported. Raising an error
     instead would turn expressions that currently evaluate quietly into
     failures, including inside profile invariants, so it is deferred rather
     than folded into 4.0.0.
   - The OS environment fallthrough that used to sit behind this entry is
     fixed: `FhirPathEnvironment.getEnvironmentContext` no longer reads
     `sys.env`, so an expression cannot resolve a process secret. Guarded by
     `FhirPathEnvironmentVariableTest`.
