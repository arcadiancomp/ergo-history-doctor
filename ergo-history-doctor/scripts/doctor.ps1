param(
    [Parameter(Mandatory = $true)]
    [string]$ErgoJar,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$DoctorArgs
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path -LiteralPath $ErgoJar -PathType Leaf)) {
    Write-Error "Ergo JAR not found: $ErgoJar"
    exit 2
}

$toolJar = Get-ChildItem -Path "$root\target\scala-2.12" -Filter 'ergo-history-doctor_2.12-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $toolJar) {
    Write-Error "Tool JAR not found. Run: .\scripts\build.ps1 -ErgoJar '$ErgoJar'"
    exit 3
}

$resolvedErgo = (Resolve-Path -LiteralPath $ErgoJar).Path
$cp = "$($toolJar.FullName);$resolvedErgo"

& java -cp $cp org.ergoplatform.nodeView.history.ErgoHistoryDoctor @DoctorArgs
exit $LASTEXITCODE
