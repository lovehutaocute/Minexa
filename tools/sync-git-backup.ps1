<#
.SYNOPSIS
    Mirror the working repository into the local bare backup and verify parity.

.DESCRIPTION
    The post-commit / post-rewrite / pre-push hooks do this automatically.
    Run this script manually after moving a tag with `git tag -f` (tag moves do
    not trigger any hook) or after importing refs from elsewhere.

.PARAMETER Repo
    Working repository path. Defaults to the parent of this script.

.EXAMPLE
    pwsh tools/sync-git-backup.ps1
#>
[CmdletBinding()]
param(
    [string]$Repo
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Repo)) {
    $scriptPath = $PSCommandPath
    if ([string]::IsNullOrWhiteSpace($scriptPath)) { $scriptPath = $MyInvocation.MyCommand.Path }
    $Repo = Split-Path -Parent (Split-Path -Parent $scriptPath)
}
$Repo = (Resolve-Path -LiteralPath $Repo).Path
$source = Join-Path $Repo '.git'
$mirror = "$Repo.git-backup"

if (-not (Test-Path -LiteralPath $source)) { throw "Not a git repository: $Repo" }
if (-not (Test-Path -LiteralPath $mirror)) { throw "Backup mirror not found: $mirror" }

& git --git-dir=$mirror fetch --quiet --force $source `
    '+refs/heads/*:refs/heads/*' `
    '+refs/tags/*:refs/tags/*' `
    '+refs/stash:refs/backup/stash'
if ($LASTEXITCODE -ne 0) { throw 'mirror fetch failed' }

$mainRefs = @{}
& git -C $Repo for-each-ref --format='%(refname) %(objectname)' | ForEach-Object {
    $name, $sha = $_ -split ' ', 2
    $mainRefs[$name] = $sha
}

$checked = 0
$mismatch = @()
foreach ($name in $mainRefs.Keys) {
    if ($name -like 'refs/remotes/*') { continue }

    $mirrorName = if ($name -eq 'refs/stash') { 'refs/backup/stash' } else { $name }
    $sha = (& git --git-dir=$mirror rev-parse --verify --quiet $mirrorName) 2>$null
    $checked++
    if ($LASTEXITCODE -ne 0 -or $sha -ne $mainRefs[$name]) {
        $mismatch += "$name  main=$($mainRefs[$name])  mirror=$sha"
    }
}

if ($checked -eq 0) { throw 'no refs found in working repository' }
if ($mismatch.Count -gt 0) {
    Write-Host "MISMATCH ($($mismatch.Count)/$checked):" -ForegroundColor Red
    $mismatch | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "OK: $checked refs match between working repository and mirror."
