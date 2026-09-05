$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..\services\toolhelper-agent')
$files = Get-ChildItem -LiteralPath $root -Recurse -File -Include *.cs,*.csproj |
  Where-Object { $_.FullName -notmatch '\\(bin|obj)\\' }
$matches = $files | Select-String -Pattern '(?i)sqlite|microsoft\.data\.sqlite|system\.data\.sqlite|\.db'
if ($matches) {
  $matches | ForEach-Object { Write-Error "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
  exit 1
}
Write-Output 'C# Agent SQLite static scan passed'
