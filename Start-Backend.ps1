#requires -Version 7.0
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$mallProject = $PSScriptRoot
$mallBackendJar = Join-Path $mallProject 'target\sports-mall-0.3.2.jar'
if (-not (Test-Path -LiteralPath $mallBackendJar)) {
    throw '请先在项目目录执行 .\mvnw.cmd package，生成后端程序。'
}
$mallJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$mallPreviousPassword = $env:MALL_DB_PASSWORD
try {
    if (-not $env:MALL_DB_PASSWORD) {
        $env:MALL_DB_PASSWORD = Read-Host '启动后端：输入 MySQL 连接密码（商城用户无需知道）' -MaskInput
    }
    if (-not $env:MALL_DB_PASSWORD) { throw 'MySQL 连接密码不能为空。' }
    Push-Location -LiteralPath $mallProject
    try {
        & $mallJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $mallBackendJar
        if ($LASTEXITCODE -ne 0) { throw "商城后端退出，退出码：$LASTEXITCODE。请查看上面的错误。" }
    } finally { Pop-Location }
} finally {
    if ($null -eq $mallPreviousPassword) { Remove-Item Env:MALL_DB_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:MALL_DB_PASSWORD = $mallPreviousPassword }
    $mallPreviousPassword = $null
}
