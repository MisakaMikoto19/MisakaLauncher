<#
.SYNOPSIS
    打包发布：构建并把发布产物收集到项目根目录 dist/。

.DESCRIPTION
    产物全部落在 dist/：
        MisakaLauncher-<版本>-<平台>-portable.exe        单文件发布包（推荐分发）
        MisakaLauncher-<版本>-<平台>-portable.zip        免安装版归档（带顶层目录）
        <可选> MisakaLauncher-<版本>.msi                 平台安装包（需 -Installer）
        SHA256SUMS.txt / release.json / README.txt               校验、清单与使用说明

    重复执行不会重复编译：Gradle 会自动跳过未变化的步骤，因此不必先判断「要不要重新构建」。

.PARAMETER SkipTests
    跳过单元测试。发布前不建议跳过。

.PARAMETER Installer
    先生成平台安装包并一并收入 dist/（Windows 需要 WiX Toolset，缺失时会失败）。

.PARAMETER Open
    完成后打开 dist 目录。

.EXAMPLE
    .\release.ps1
    .\release.ps1 -SkipTests
    .\release.ps1 -Installer -Open
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Installer,
    [switch]$Open
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# 保证控制台以 UTF-8 输出，避免中文日志乱码
$OutputEncoding = [System.Text.Encoding]::UTF8

$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw '未找到 gradlew.bat，请确认在项目根目录执行。'
}

$distDir = Join-Path $PSScriptRoot 'dist'

<#
    调用原生命令时必须临时关闭 Stop 模式：
    原生程序的 stderr 在 $ErrorActionPreference = 'Stop' 下会被 PowerShell 当作终止性错误抛出
    （例如 JVM 的 "Picked up JAVA_TOOL_OPTIONS" 提示），导致明明构建成功却报错退出。
    正确做法是只看退出码。
#>
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

function Invoke-Gradle {
    param([string[]]$GradleTasks)

    Write-Host "==> gradlew $($GradleTasks -join ' ')" -ForegroundColor Cyan
    & $gradlew @GradleTasks '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 执行失败，退出码 $LASTEXITCODE"
    }
}

try {
    # 安装包必须单独跑完：releaseDist 是在 doLast 里从 build_windows/ 收集产物的，
    # 与 packageInstaller 放进同一次调用无法保证先后顺序
    if ($Installer) {
        Invoke-Gradle @(':app:packageInstaller')
    }

    $tasks = @()
    if (-not $SkipTests) {
        $tasks += ':core:test'
    }
    $tasks += ':app:releaseDist'
    Invoke-Gradle $tasks
} finally {
    $ErrorActionPreference = $previousPreference
}

if (-not (Test-Path -LiteralPath $distDir)) {
    throw "发布完成但未找到 dist 目录: $distDir"
}

Write-Host ''
Write-Host '==> 发布产物（dist/）' -ForegroundColor Green
Get-ChildItem -LiteralPath $distDir | ForEach-Object {
    if ($_.PSIsContainer) {
        Write-Host ("    {0}\" -f $_.Name)
    } else {
        Write-Host ("    {0,-56} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB))
    }
}

$portable = Get-ChildItem -LiteralPath $distDir -Filter '*-portable.*' -ErrorAction SilentlyContinue |
    Where-Object { -not $_.PSIsContainer -and $_.Extension -ne '.zip' } |
    Select-Object -First 1
if ($portable) {
    Write-Host ''
    Write-Host ("对外分发只需这一个文件: {0}" -f $portable.FullName) -ForegroundColor Yellow
}

if ($Open) {
    Start-Process -FilePath $distDir
}
