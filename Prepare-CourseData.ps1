$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
[xml]$mallPom = Get-Content -Raw -LiteralPath '.\pom.xml'
$mallVersion = $mallPom.project.version
$mallJar = Join-Path $PSScriptRoot "target\sports-mall-$mallVersion.jar"
$mallJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Test-Path -LiteralPath $mallJar)) { throw '请先在工程目录构建：.\mvnw.cmd package' }
$mallEvidence = Join-Path $PSScriptRoot 'target\course-data'
New-Item -ItemType Directory -Path $mallEvidence -Force | Out-Null
Write-Host '本工具添加明确标注的课程演示数据，并创建两个查询索引。已有数据保留。'
Write-Host '目标：本机 MySQL 18306 / MongoDB 27017 的 sports_mall。不会注册服务。'
Write-Host '演示日志通过原有 JDBC 导入流程保存；随机演示账号密码不保存、不提供默认密码。'
try {
    if (-not $env:MALL_DB_PASSWORD) {
        $env:MALL_DB_PASSWORD = Read-Host '请输入 MySQL 连接密码（仅本地使用，输入不回显）' -MaskInput
    }
    foreach ($mallLabel in @('first', 'repeat')) {
        & $mallJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-cp' $mallJar '.\scripts\java\CourseDataTool.java' '--apply-course' '18306' '27017' (Join-Path $mallEvidence "$mallLabel.json")
        if ($LASTEXITCODE -ne 0) { throw "演示数据工具失败（$mallLabel），请保留错误信息。跨库已保存部分可重跑继续。" }
    }
    $mallFirst = Get-Content -Raw -LiteralPath (Join-Path $mallEvidence 'first.json') | ConvertFrom-Json
    $mallRepeat = Get-Content -Raw -LiteralPath (Join-Path $mallEvidence 'repeat.json') | ConvertFrom-Json
    if (($mallFirst.after | ConvertTo-Json -Compress) -ne ($mallRepeat.after | ConvertTo-Json -Compress) -or
        ($mallRepeat.before | ConvertTo-Json -Compress) -ne ($mallRepeat.after | ConvertTo-Json -Compress)) {
        throw '两次执行之间数量发生变化，请检查是否有其他窗口同时写入。'
    }
    if (-not $mallFirst.existing_records_unchanged -or -not $mallRepeat.existing_records_unchanged) {
        throw '已有记录指纹发生变化，请检查报告。'
    }
    $mallFirst.after | Format-List
    'SUCCESS' | Set-Content -LiteralPath (Join-Path $mallEvidence 'status.txt') -Encoding utf8
    Write-Host '完成：配额达标，已有记录指纹保持，重跑没有重复增加数据。'
} catch {
    'FAILED' | Set-Content -LiteralPath (Join-Path $mallEvidence 'status.txt') -Encoding utf8
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally {
    Remove-Item Env:MALL_DB_PASSWORD -ErrorAction SilentlyContinue
}
