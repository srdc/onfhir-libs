# Compares current reusable artifacts with the last public 3.3 release using
# MiMa CLI. The committed baseline represents intentional breaks reconciled
# with the migration guide. ASCII-only for Windows PowerShell 5.1.

param(
    [string]$PreviousVersion = "3.3",
    [switch]$UpdateBaseline,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$baseline = Join-Path $repoRoot "docs\compatibility\mima-3.3-accepted.txt"
$scratch = Join-Path ([IO.Path]::GetTempPath()) ("onfhir-mima-" + [Guid]::NewGuid().ToString("N"))
$oldDir = Join-Path $scratch "old"
New-Item -ItemType Directory -Path $oldDir -Force | Out-Null

$artifacts = [ordered]@{
    "onfhir-common" = "onfhir-common_2.13"
    "onfhir-client" = "onfhir-client_2.13"
    "onfhir-path" = "onfhir-path_2.13"
    "onfhir-query" = "onfhir-query_2.13"
    "onfhir-config" = "onfhir-config_2.13"
    "onfhir-expression" = "onfhir-expression_2.13"
    "onfhir-validation" = "onfhir-validation_2.13"
    "onfhir-template-engine" = "onfhir-template-engine_2.13"
    # Resources-only artifacts: no Scala binary-version suffix and no classes to
    # compare, but they are still tracked so the report covers every published
    # coordinate.
    "onfhir-definitions-r4" = "onfhir-definitions-r4"
    "onfhir-definitions-r5" = "onfhir-definitions-r5"
    "onfhir-definitions-stu3" = "onfhir-definitions-stu3"
    "onfhir-r4" = "onfhir-r4_2.13"
    "onfhir-r5" = "onfhir-r5_2.13"
    "onfhir-stu3" = "onfhir-stu3_2.13"
}
$newArtifacts = @("onfhir-query_2.13", "onfhir-template-engine_2.13",
    "onfhir-definitions-r4", "onfhir-definitions-r5", "onfhir-definitions-stu3",
    "onfhir-r5_2.13", "onfhir-stu3_2.13")

$modules = ($artifacts.Keys -join ",")
Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & mvn -B -pl $modules -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Current library artifact build failed." }
    }

    $reportLines = @(
        "# MiMa accepted compatibility report",
        "# Baseline: io.onfhir reusable artifacts $PreviousVersion",
        "# Every reported break must have a corresponding migration-guide entry.",
        "# Reconciliation: docs/compatibility/mima-3.3-reconciliation.md",
        ""
    )
    foreach ($entry in $artifacts.GetEnumerator()) {
        $module = $entry.Key
        $artifact = $entry.Value
        $currentJar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "$module\target") -Filter "$artifact-*.jar" |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $currentJar) { throw "Current JAR not found for $artifact" }

        if ($newArtifacts -contains $artifact) {
            $reportLines += "## $artifact"
            $reportLines += "NEW-ARTIFACT: no $PreviousVersion artifact was available"
            $reportLines += ""
            continue
        }

        $copyArgs = @(
            "-q", "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy",
            "-Dartifact=io.onfhir:${artifact}:$PreviousVersion",
            "-DoutputDirectory=$oldDir",
            "-Dmdep.stripVersion=true"
        )
        & mvn @copyArgs
        if ($LASTEXITCODE -ne 0) {
            $reportLines += "## $artifact"
            $reportLines += "NEW-ARTIFACT: no $PreviousVersion artifact was available"
            $reportLines += ""
            continue
        }

        $oldJar = Join-Path $oldDir "$artifact.jar"
        $savedErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $mimaOutput = @(& cs launch com.typesafe:mima-cli_3:1.1.5 -- $oldJar $currentJar.FullName 2>&1)
        $mimaExit = $LASTEXITCODE
        $ErrorActionPreference = $savedErrorPreference
        $normalized = $mimaOutput | ForEach-Object {
            $_.ToString().Replace($oldJar, "<OLD-JAR>").Replace($currentJar.FullName, "<CURRENT-JAR>")
        } | Where-Object { $_ -notmatch '^\s*$' -and $_ -notmatch '^NOTE: Picked up JDK_JAVA_OPTIONS:' }
        $reportLines += "## $artifact"
        if ($normalized.Count -eq 0 -and $mimaExit -eq 0) {
            $reportLines += "COMPATIBLE"
        } else {
            $reportLines += $normalized
        }
        $reportLines += ""
    }
} finally {
    Pop-Location
}

$report = ($reportLines -join [Environment]::NewLine).TrimEnd() + [Environment]::NewLine
if ($UpdateBaseline) {
    $parent = Split-Path -Parent $baseline
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    # Write UTF-8 without a BOM and without an extra trailing newline. Set-Content
    # -Encoding UTF8 emits a BOM on Windows PowerShell 5.1 but not on PowerShell 7,
    # and appends its own line ending to a value that already ends with one, so the
    # baseline would change depending on which edition regenerated it.
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($baseline, $report, $utf8NoBom)
    Write-Output ("Updated MiMa baseline: {0}" -f $baseline)
    exit 0
}
if (-not (Test-Path $baseline)) {
    throw "MiMa baseline is missing. Review and create it with -UpdateBaseline."
}
$expected = Get-Content -LiteralPath $baseline -Raw
$expectedNormalized = ($expected -replace "`r`n", "`n").TrimEnd()
$reportNormalized = ($report -replace "`r`n", "`n").TrimEnd()
if ($reportNormalized -ne $expectedNormalized) {
    Write-Output "MiMa report differs from the accepted baseline."
    Compare-Object ($expectedNormalized -split "`n") ($reportNormalized -split "`n") |
        ForEach-Object { Write-Output ("  {0} {1}" -f $_.SideIndicator, $_.InputObject) }
    Write-Output "Run with -UpdateBaseline only after reconciling changes with the migration guide."
    exit 1
}
Write-Output "check-binary-compatibility: PASS"
# Explicit, because $LASTEXITCODE still holds the exit code of the last MiMa
# invocation, which is nonzero whenever that artifact had breaks - including the
# ones already accepted in the baseline. The `shell: pwsh` steps in CI exit with
# $LASTEXITCODE, so without this the job fails on a passing run.
exit 0
