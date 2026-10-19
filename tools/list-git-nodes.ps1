<#
.SYNOPSIS
    List every git ref (branch, tag, stash) of this project and its backup mirror.

.DESCRIPTION
    Reads refs via `git for-each-ref`, so packed refs and loose refs are both
    included. This is the reliable way to see all nodes: `git tag`, `git branch`
    and the .git/refs directory each show only a subset.

.PARAMETER Repo
    Working repository path. Defaults to the parent of this script.

.PARAMETER BackupOnly
    Only show safety nodes: backup/*, checkpoint*, safety/* and stash.

.PARAMETER NoMirror
    Skip the local bare mirror repository.

.EXAMPLE
    pwsh tools/list-git-nodes.ps1

.EXAMPLE
    pwsh tools/list-git-nodes.ps1 -BackupOnly
#>
[CmdletBinding()]
param(
    [string]$Repo,
    [switch]$BackupOnly,
    [switch]$NoMirror
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Repo)) {
    $scriptPath = $PSCommandPath
    if ([string]::IsNullOrWhiteSpace($scriptPath)) { $scriptPath = $MyInvocation.MyCommand.Path }
    $Repo = Split-Path -Parent (Split-Path -Parent $scriptPath)
}
$Repo = (Resolve-Path -LiteralPath $Repo).Path
if (-not (Test-Path -LiteralPath (Join-Path $Repo '.git'))) {
    throw "Not a git working repository: $Repo"
}

$format = '%(creatordate:short)  %(objectname:short)  %(refname)  %(subject)'
$backupPattern = 'refs/(tags/(backup|checkpoint)|heads/safety|stash|backup/)'

function Show-Refs {
    param([string[]]$GitArgs, [string]$Title)

    Write-Host ''
    Write-Host "== $Title ==" -ForegroundColor Cyan

    $lines = & git @GitArgs for-each-ref --sort=-creatordate --format=$format
    if ($LASTEXITCODE -ne 0) { throw "git for-each-ref failed for $Title" }
    if ($BackupOnly) { $lines = $lines | Where-Object { $_ -match $backupPattern } }
    if (-not $lines) { Write-Host '   (none)'; return }

    $rows = foreach ($line in $lines) {
        $parts = $line -split '  ', 4
        [pscustomobject]@{
            Date    = $parts[0]
            Commit  = $parts[1]
            Ref     = $parts[2]
            Subject = $parts[3]
        }
    }
    ($rows | Format-Table -AutoSize | Out-String -Width 240).TrimEnd() | Write-Host
}

Show-Refs -GitArgs @('-C', $Repo) -Title "working repo: $Repo"

$mirror = "$Repo.git-backup"
if (-not $NoMirror -and (Test-Path -LiteralPath $mirror)) {
    Show-Refs -GitArgs @("--git-dir=$mirror") -Title "backup mirror: $mirror"
}
