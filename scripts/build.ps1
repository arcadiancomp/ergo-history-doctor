param(
    [Parameter(Mandatory = $true)]
    [string]$ErgoJar
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path -LiteralPath $ErgoJar -PathType Leaf)) {
    Write-Error "Ergo JAR not found: $ErgoJar"
    exit 2
}

$resolvedJar = (Resolve-Path -LiteralPath $ErgoJar).Path
$env:ERGO_JAR = $resolvedJar

Push-Location $root
try {
    & "$PSScriptRoot\sbt.ps1" clean package
    $rc = $LASTEXITCODE
    if ($rc -ne 0) {
        Write-Error "Build failed with exit code $rc"
        exit $rc
    }

    $built = Get-ChildItem -Path "$root\target\scala-2.12" -Filter 'ergo-history-doctor_2.12-*.jar' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $built) {
        Write-Error 'Build completed but the tool JAR was not found.'
        exit 3
    }

    Write-Host ""
    Write-Host "Built: $($built.FullName)"
    Write-Host "Against Ergo JAR: $resolvedJar"
}
finally {
    Pop-Location
}
