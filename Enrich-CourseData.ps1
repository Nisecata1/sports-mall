$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
[xml]$mallPom = Get-Content -LiteralPath 'pom.xml' -Raw
$mallJar = Join-Path $PSScriptRoot "target\sports-mall-$($mallPom.project.version).jar"
$mallJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
$mallCompiler = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$mallClasses = Join-Path $PSScriptRoot 'target\catalog-enrichment-classes'
$mallRun = Join-Path $env:LOCALAPPDATA ('CodexProjects\sports-mall-backups\catalog-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $mallClasses,$mallRun -Force | Out-Null
$mallRun | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'target\catalog-enrichment-current.txt') -Encoding utf8
Write-Host '将新增32件具体运动商品、9个分类、96笔分月模拟订单、128条评价及差异化浏览记录。'
Write-Host '备份与回执保存在本机目录' $mallRun
$mallPreviousPassword = $env:MALL_DB_PASSWORD
try {
    & $mallCompiler '-encoding' 'UTF-8' '-cp' $mallJar '-d' $mallClasses 'scripts\java\CourseDataTool.java' 'scripts\java\CourseCatalogEnricher.java'
    if ($LASTEXITCODE -ne 0) { throw '数据工具编译失败' }
    if (-not $env:MALL_DB_PASSWORD) {
        $env:MALL_DB_PASSWORD = Read-Host '输入MySQL连接密码，仅用于本次本地执行' -MaskInput
    }
    foreach ($mallPhase in @('first','repeat')) {
        & $mallJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-cp' "$mallClasses;$mallJar" 'CourseCatalogEnricher' '--apply-course' '18306' '27017' (Join-Path $mallRun $mallPhase)
        if ($LASTEXITCODE -ne 0) { throw "执行失败，请保留备份及回执。阶段 $mallPhase" }
    }
    $mallFirst = Get-Content -LiteralPath (Join-Path $mallRun 'first\receipt.json') -Raw | ConvertFrom-Json
    $mallRepeat = Get-Content -LiteralPath (Join-Path $mallRun 'repeat\receipt.json') -Raw | ConvertFrom-Json
    if (($mallRepeat.before | ConvertTo-Json -Compress) -ne ($mallRepeat.after | ConvertTo-Json -Compress)) {
        throw '重复执行的数量核对失败，请检查回执'
    }
    if (-not $mallFirst.existing_records_unchanged -or -not $mallRepeat.existing_records_unchanged) {
        throw '已有数据指纹核对失败，请检查备份'
    }
    $mallRepeat.after | Format-List
    'SUCCESS' | Set-Content -LiteralPath (Join-Path $mallRun 'status.txt') -Encoding utf8
    Write-Host '完成。进入商城演示分类即可查看具体商品及统计样本。'
} catch {
    'FAILED' | Set-Content -LiteralPath (Join-Path $mallRun 'status.txt') -Encoding utf8
    Write-Host $_.Exception.Message -ForegroundColor Red
    throw
} finally {
    if ($null -eq $mallPreviousPassword) { Remove-Item Env:MALL_DB_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:MALL_DB_PASSWORD = $mallPreviousPassword }
    $mallPreviousPassword = $null
}
Read-Host '按回车关闭窗口'
