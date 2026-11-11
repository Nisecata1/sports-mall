#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)][int]$MySqlPort = 18306,
    [ValidateRange(1024, 65535)][int]$MongoPort = 27017,
    [ValidateRange(1024, 65535)][int]$ApiPort = $(if ($env:MALL_API_PORT) { [int]$env:MALL_API_PORT } else { 18808 }),
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$checks = [Collections.Generic.List[object]]::new()
function Add-Check([string]$Name, [bool]$Passed, [string]$Message) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; message = $Message })
}
function Test-LocalPort([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.ConnectAsync('127.0.0.1', $Port)
        if (-not $pending.Wait(1500)) { return $false }
        return $client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}
function Read-Api([string]$Path) {
    try {
        $status = 0
        $body = Invoke-RestMethod -Uri "http://127.0.0.1:$ApiPort$Path" -TimeoutSec 15 `
            -SkipHttpErrorCheck -StatusCodeVariable status -MaximumRedirection 0
        return [pscustomobject]@{ status = $status; body = $body }
    } catch { return [pscustomobject]@{ status = 0; body = $null } }
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
try {
    $javaVersion = (& $java -version 2>&1 | Out-String).Trim()
    $versionMatch = [regex]::Match($javaVersion, 'version "(?<major>\d+)')
    $javaOk = $LASTEXITCODE -eq 0 -and $versionMatch.Success -and [int]$versionMatch.Groups['major'].Value -ge 17
    Add-Check 'Java' $javaOk $(if ($javaOk) { "Java $($versionMatch.Groups['major'].Value)，满足17及以上要求" } else { '请安装Java 17及以上并检查JAVA_HOME或PATH' })
} catch { Add-Check 'Java' $false '找不到Java，请检查JAVA_HOME或PATH' }

[xml]$pom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw
$version = [string]$pom.project.version
$jar = Join-Path $PSScriptRoot "target\sports-mall-$version.jar"
$desktop = Join-Path $PSScriptRoot "target\sports-mall-$version-desktop.jar"
$builtAt = $null
Add-Check '前后端程序包' ((Test-Path -LiteralPath $jar) -and (Test-Path -LiteralPath $desktop)) '缺少程序包时，在工程目录运行 .\mvnw.cmd package'
if (Test-Path -LiteralPath $jar) {
    $archive = $null
    $reader = $null
    try {
        $archive = [IO.Compression.ZipFile]::OpenRead($jar)
        $entry = $archive.GetEntry('build-info.properties')
        if ($null -ne $entry) {
            $reader = [IO.StreamReader]::new($entry.Open())
            $properties = ConvertFrom-StringData -StringData $reader.ReadToEnd()
            if ($properties['application.version'] -eq $version) { $builtAt = $properties['application.builtAt'] }
        }
    } catch { $builtAt = $null }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $archive) { $archive.Dispose() }
    }
}
Add-Check '构建信息' (-not [string]::IsNullOrEmpty($builtAt) -and $builtAt -notmatch '\$\{') '程序包应包含与pom.xml一致的版本及构建时间；缺失时重新构建'

Add-Check 'MySQL端口' (Test-LocalPort $MySqlPort) "127.0.0.1:$MySqlPort，失败时双击 C:\develop\MySQL\启动MySQL.cmd"
Add-Check 'MongoDB端口' (Test-LocalPort $MongoPort) "127.0.0.1:$MongoPort，失败时双击 C:\develop\MongoDB\启动MongoDB.cmd"
$health = Read-Api '/api/health'
$isMall = $health.status -eq 200 -and $health.body.application -eq 'sports-mall' -and $health.body.status -eq 'UP'
Add-Check '商城后端' $isMall "http://127.0.0.1:$ApiPort，失败时启动 Start-Backend.cmd；端口响应不等于商城后端正常"
if ($isMall) {
    # PowerShell 7.5及以上可能把JSON中的ISO时间自动解析为DateTime。
    $runningBuild = if ($health.body.builtAt -is [datetime]) {
        $health.body.builtAt.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
    } else { [string]$health.body.builtAt }
    $sameBuild = -not [string]::IsNullOrEmpty($builtAt) -and $health.body.version -eq $version -and $runningBuild -eq $builtAt
    Add-Check '运行版本与构建' $sameBuild "运行版本=$($health.body.version)，构建=$runningBuild；本地版本=$version，构建=$builtAt。不同请退出旧后端并重新启动"
    $ready = Read-Api '/api/ready'
    $readyOk = $ready.status -eq 200 -and $ready.body.status -eq 'UP' -and $ready.body.dependencies.mysql -eq 'UP' -and $ready.body.dependencies.mongodb -eq 'UP'
    Add-Check '后端数据库连接' $readyOk "MySQL=$($ready.body.dependencies.mysql)，MongoDB=$($ready.body.dependencies.mongodb)。失败时检查数据库服务和后端连接配置"
} else {
    Add-Check '后端数据库连接' $false '后端尚未确认运行，暂不能验证它连接数据库的结果'
}
$allPassed = @($checks | Where-Object { -not $_.passed }).Count -eq 0
$report = [pscustomobject]@{
    checked_at = (Get-Date).ToUniversalTime().ToString('o')
    passed = $allPassed
    scope = '本机演示环境只读检查；端口仅验证TCP响应，就绪接口仅验证数据库连接，不替代业务验收'
    checks = $checks.ToArray()
}
if ($Json) { $report | ConvertTo-Json -Depth 5 }
else {
    foreach ($check in $checks) {
        $label = if ($check.passed) { '通过' } else { '待处理' }
        Write-Host "[$label] $($check.name)  $($check.message)"
    }
    Write-Host $report.scope
}
if (-not $allPassed) { exit 1 }
exit 0
