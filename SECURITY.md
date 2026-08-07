# Security Policy

The onFHIR reusable libraries process healthcare data formats and are used
inside clinical systems. We take vulnerability reports seriously and prefer
coordinated disclosure.

## Reporting a vulnerability

Please do NOT open a public GitHub issue for a suspected vulnerability.

- Preferred: use GitHub private vulnerability reporting on
  `srdc/onfhir-libs` (Security tab, "Report a vulnerability").
- Alternatively, email `onfhir@srdc.com.tr`.

Include the affected artifact and version, a description, and reproduction
steps or a proof of concept if available. We aim to acknowledge reports
within five business days.

## Supported versions

| Version | Supported |
|---|---|
| 4.0.x | yes |
| 3.x and earlier (monorepo line) | no |

## Scope notes

- FHIRPath expressions, profiles, search parameter definitions, and
  templates are treated as **content, not trusted code**: library behavior
  that lets such content read process state or the environment is a
  vulnerability (see the 4.0.0 fix removing the FHIRPath `%name`
  fallthrough to `sys.env`).
- Vulnerabilities in the Repofyr FHIR server should be reported to the
  Repofyr repository, not here.
