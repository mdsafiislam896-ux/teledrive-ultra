param(
  [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
Write-Host "NexaCloud web app: http://127.0.0.1:$Port/"
py -m http.server $Port --bind 127.0.0.1
