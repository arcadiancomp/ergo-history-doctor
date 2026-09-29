param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$SbtArgs
)

$ErrorActionPreference = 'Stop'

$existing = Get-Command sbt -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    & $existing.Source @SbtArgs
    exit $LASTEXITCODE
}

$version = '1.10.11'
$cacheRoot = Join-Path $env:LOCALAPPDATA 'ErgoHistoryDoctor\tools'
$launcher = Join-Path $cacheRoot "sbt-launch-$version.jar"

if (-not (Test-Path -LiteralPath $launcher)) {
    New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
    $url = "https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/$version/sbt-launch-$version.jar"
    Write-Host "Downloading sbt launcher $version ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $launcher
}

& java -jar $launcher @SbtArgs
exit $LASTEXITCODE
