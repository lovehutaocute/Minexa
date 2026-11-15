# 一键部署脚本（Windows PowerShell）
#
# 用法：在 cloudflare-crash 目录下打开 PowerShell，执行
#     .\deploy.ps1
#
# 需要你本人参与的地方只有两处（都是打开浏览器点一下 / 抄一串字符）：
#   1. 第一次运行会让你登录 Cloudflare（wrangler login 弹浏览器）
#   2. 会给出一串 REPORT_KEY，你要抄下来（以后网页上再也看不到）

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

function Step {
    param([int]$n, [string]$title)
    Write-Host ""
    Write-Host "--------------------------------------" -ForegroundColor DarkGray
    Write-Host " Step $n : $title" -ForegroundColor Cyan
    Write-Host "--------------------------------------" -ForegroundColor DarkGray
}

function Ok {
    param([string]$msg)
    Write-Host "  [OK] $msg" -ForegroundColor Green
}

function Warn {
    param([string]$msg)
    Write-Host "  [!!] $msg" -ForegroundColor Yellow
}

function Die {
    param([string]$msg)
    Write-Host "  [XX] $msg" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Minexa Crash Reporter - Deploy" -ForegroundColor White
Write-Host "dir: $here" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
Step 1 'check wrangler'

$wr = Get-Command wrangler -ErrorAction SilentlyContinue
if (-not $wr) {
    Warn 'wrangler not found, installing...'
    npm install -g --allow-scripts=esbuild,workerd wrangler
    if ($LASTEXITCODE -ne 0) {
        Die 'install failed. try: npm install -g wrangler'
    }
}
$wrVersion = wrangler --version 2>&1 | Out-String
Ok ("wrangler " + $wrVersion.Trim())

# ---------------------------------------------------------------------------
Step 2 'login cloudflare'

Write-Host "  A browser will open. Approve with your own Cloudflare account." -ForegroundColor Yellow
Read-Host "  Press Enter when ready"

wrangler login
if ($LASTEXITCODE -ne 0) {
    Die 'login failed. run this script again to retry.'
}
Ok 'logged in'

# ---------------------------------------------------------------------------
Step 3 'create D1 database'

Write-Host "  creating D1 database: minexa-crash ..."
$createOut = (wrangler d1 create minexa-crash 2>&1 | Out-String)
Write-Host $createOut -ForegroundColor DarkGray

$dbId = $null
if ($createOut -match 'database_id\s*=\s*"([^"]+)"') {
    $dbId = $Matches[1]
}

if (-not $dbId) {
    if ($createOut -match 'already exists') {
        Warn 'database already exists, skipping create.'
        Write-Host "  get id from: Cloudflare dashboard > Storage & Databases > D1 > minexa-crash" -ForegroundColor Yellow
        $dbId = Read-Host "  paste database_id here"
    }
    else {
        Die 'could not read database_id. send the output above to the AI.'
    }
}

$dbId = $dbId.Trim()

$tomlPath = Join-Path $here 'wrangler.toml'
$toml = Get-Content $tomlPath -Raw
$toml = $toml -replace 'database_id\s*=\s*"[^"]*"', "database_id = `"$dbId`""
Set-Content $tomlPath $toml -NoNewline -Encoding UTF8
Ok "database_id written to wrangler.toml: $dbId"

# ---------------------------------------------------------------------------
Step 4 'create table'

wrangler d1 execute minexa-crash --remote --file=./schema.sql
if ($LASTEXITCODE -ne 0) {
    Die 'schema failed. see error above.'
}
Ok 'table created (crashes)'

# ---------------------------------------------------------------------------
Step 5 'set report key'

Write-Host "  generating a random 32-char key ..."
$key = -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })

Write-Host ""
Write-Host "  ===== COPY THIS KEY DOWN =====" -ForegroundColor Yellow
Write-Host "  $key" -ForegroundColor White
Write-Host "  ==============================" -ForegroundColor Yellow
Write-Host ""

$key | wrangler secret put REPORT_KEY
if ($LASTEXITCODE -ne 0) {
    Warn 'auto set failed. run manually:'
    Write-Host "      wrangler secret put REPORT_KEY" -ForegroundColor Yellow
    Write-Host "      then paste: $key" -ForegroundColor Yellow
}
else {
    Ok 'secret set'
}

# ---------------------------------------------------------------------------
Step 6 'deploy'

$deployOut = (wrangler deploy 2>&1 | Out-String)
Write-Host $deployOut -ForegroundColor DarkGray

$url = $null
if ($deployOut -match '(https://[a-zA-Z0-9.\-]+\.workers\.dev)') {
    $url = $Matches[1]
}

# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "======================================" -ForegroundColor Green
if ($url) {
    Ok "deployed: $url"
    Write-Host ""
    Write-Host "put these into CrashReporter.kt:" -ForegroundColor White
    Write-Host "  private const val ENDPOINT = `"$url/report`"" -ForegroundColor Cyan
    Write-Host "  private const val KEY = `"$key`"" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "your view page (bookmark it):" -ForegroundColor White
    Write-Host "  $url/list?key=$key" -ForegroundColor Cyan
}
else {
    Warn 'no workers.dev url in output. check the output above or the dashboard.'
}
Write-Host "======================================" -ForegroundColor Green
Write-Host ""
Write-Host "next: run the connectivity test (README step 6) before touching the App." -ForegroundColor White
