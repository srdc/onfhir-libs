# Verifies a file-based Maven staging repository for onFHIR libraries.
# ASCII-only for Windows PowerShell 5.1.

param(
    [Parameter(Mandatory = $true)][string]$RepositoryPath,
    [string]$Version = "4.0.0",
    [switch]$SkipSignatures
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path -LiteralPath $RepositoryPath).Path
$artifacts = [ordered]@{
    "onfhir-libs-parent" = "pom"
    "onfhir-common_2.13" = "jar"
    "onfhir-client_2.13" = "jar"
    "onfhir-path_2.13" = "jar"
    "onfhir-query_2.13" = "jar"
    "onfhir-config_2.13" = "jar"
    "onfhir-expression_2.13" = "jar"
    "onfhir-validation_2.13" = "jar"
    "onfhir-template-engine" = "jar"
    "onfhir-r4_2.13" = "jar"
    "onfhir-libs-bom" = "pom"
}

function Require-File([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing staged artifact: $path"
    }
}

function Verify-Signature([string]$path) {
    if ($SkipSignatures) { return }
    $signature = $path + ".asc"
    Require-File $signature
    & gpg --batch --verify $signature $path
    if ($LASTEXITCODE -ne 0) { throw "Invalid signature: $signature" }
}

foreach ($entry in $artifacts.GetEnumerator()) {
    $artifactId = $entry.Key
    $packaging = $entry.Value
    $artifactRoot = Join-Path $repositoryRoot ("io\onfhir\{0}\{1}" -f $artifactId, $Version)
    $pom = Join-Path $artifactRoot ("{0}-{1}.pom" -f $artifactId, $Version)
    Require-File $pom
    $pomText = Get-Content -LiteralPath $pom -Raw
    if ($pomText -notmatch 'Apache License, Version 2.0') {
        throw "Apache-2.0 metadata is missing from $pom"
    }
    if ($pomText -match 'GNU General Public License') {
        throw "GPL metadata remains in $pom"
    }
    Verify-Signature $pom

    if ($packaging -eq "jar") {
        $binary = Join-Path $artifactRoot ("{0}-{1}.jar" -f $artifactId, $Version)
        $sources = Join-Path $artifactRoot ("{0}-{1}-sources.jar" -f $artifactId, $Version)
        $javadocs = Join-Path $artifactRoot ("{0}-{1}-javadoc.jar" -f $artifactId, $Version)
        foreach ($file in @($binary, $sources, $javadocs)) {
            Require-File $file
            Verify-Signature $file
        }
        $entries = @(& jar tf $binary)
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $binary" }
        if ($entries -notcontains "META-INF/LICENSE") { throw "META-INF/LICENSE missing from $binary" }
        if ($entries -notcontains "META-INF/NOTICE") { throw "META-INF/NOTICE missing from $binary" }
    }
}

Write-Output ("check-staged-release: PASS - {0} {1} artifacts verified." -f $artifacts.Count, $Version)
