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

5. FHIRPath environment variables fall through to OS environment variables
   - `FhirPathEnvironment.getEnvironmentContext` resolves unknown `%name`
     variables from `sys.env`. This can expose process environment values to
     profile-supplied expressions and silently masks typos (the FHIRPath
     spec treats undefined environment variables as errors). A fix decision
     is pending; see the split-plan follow-ups.
