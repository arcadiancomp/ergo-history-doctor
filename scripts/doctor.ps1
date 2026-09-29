param(
    [Parameter(Mandatory = $true)]
    [string]$ErgoJar,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$DoctorArgs
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$invocationDir = (Get-Location).Path

function Resolve-DoctorPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }

    return [System.IO.Path]::GetFullPath(
        (Join-Path $invocationDir $Value)
    )
}

if (-not (Test-Path -LiteralPath $ErgoJar -PathType Leaf)) {
    Write-Error "Ergo JAR not found: $ErgoJar"
    exit 2
}

$resolvedErgo = (Resolve-Path -LiteralPath $ErgoJar).Path

$toolJar = Get-ChildItem `
    -Path "$root\target\scala-2.12" `
    -Filter 'ergo-history-doctor_2.12-*.jar' `
    -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $toolJar) {
    Write-Error "Tool JAR not found. Run: .\scripts\build.ps1 -ErgoJar '$resolvedErgo'"
    exit 3
}

#
# Normalize path-valued CLI arguments relative to the directory
# from which the user invoked the Doctor.
#
$normalizedArgs = @($DoctorArgs)
$configPath = $null

for ($i = 0; $i -lt $normalizedArgs.Count; $i++) {

    $arg = $normalizedArgs[$i]

    if ($arg -in @('--config', '--out', '--plan')) {

        if (($i + 1) -ge $normalizedArgs.Count) {
            Write-Error "Missing value for $arg"
            exit 4
        }

        $resolved = Resolve-DoctorPath $normalizedArgs[$i + 1]
        $normalizedArgs[$i + 1] = $resolved

        if ($arg -eq '--config') {
            $configPath = $resolved
        }

        $i++
    }
}

#
# Commands that open history MUST resolve Ergo's relative data
# directory from the same directory as the supplied node config.
#
$workDir = $invocationDir

if ($null -ne $configPath) {

    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        Write-Error "Config file not found: $configPath"
        exit 5
    }

    $workDir = Split-Path -Parent $configPath
}

$cp = "$($toolJar.FullName);$resolvedErgo"

Push-Location $workDir

try {
    & java `
        -cp $cp `
        org.ergoplatform.nodeView.history.ErgoHistoryDoctor `
        @normalizedArgs

    $code = $LASTEXITCODE
}
finally {
    Pop-Location
}

exit $code
