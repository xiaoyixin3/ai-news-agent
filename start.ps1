param([switch]$Rebuild)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install Java 17 or newer, then reopen the terminal.' }
$agentJar = Join-Path $PSScriptRoot 'target\ai-news-agent.jar'
if ($Rebuild -or -not (Test-Path -LiteralPath $agentJar)) {
    & "$PSScriptRoot\mvnw.cmd" -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. Check Java version and Maven Central connectivity.' }
}
Write-Host 'Open http://127.0.0.1:8080 (or your configured PORT). Ctrl+C stops the service.'
& java -jar $agentJar
exit $LASTEXITCODE
