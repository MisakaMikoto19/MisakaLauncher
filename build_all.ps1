<#
.SYNOPSIS
    一键构建 Misaka Launcher（Windows）。

.DESCRIPTION
    统一入口，构建并把发布产物收集到项目根目录 dist/：
        dist/MisakaLauncher-<版本>-windows-portable.exe  —— 单文件发布包（推荐分发）
        dist/MisakaLauncher-<版本>-windows-portable.zip  —— 免安装版归档（带顶层目录）
        dist/MisakaLauncher-<版本>-windows-amd64.jar     —— 可执行 jar（交叉编译，目标机需自备 Java 17）
        dist/MisakaLauncher-<版本>-linux-amd64.jar       —— 同上，Linux 版；两个平台一次构建一起产出
        dist/SHA256SUMS.txt / release.json / README.txt           —— 校验、清单与使用说明

    jar 与单文件包是两种形态：jar 不带 JVM（因此能在 Windows 上交叉构建 Linux 版），
    单文件包自带运行时（目标机免装 Java，但只能在本平台构建）。

    中间产物（免安装版目录、自带 Java 运行时）仍保留在 build_windows/，便于本地调试。

    单文件发布包用系统自带的 csc.exe（.NET Framework 4.x）编译自解压外壳，
    不需要 WiX 或 7-Zip Extra；首次运行解压到 %LOCALAPPDATA%，之后启动是瞬时的。

    构建依赖全部走国内镜像：
        - Gradle 分发包  : 腾讯云镜像（见 gradle/wrapper/gradle-wrapper.properties）
        - Maven 依赖仓库 : 阿里云镜像（见 settings.gradle.kts）

.PARAMETER SkipTests
    跳过单元测试，仅编译与打包。

.PARAMETER Installer
    额外生成 jpackage 安装包（Windows 需要 WiX Toolset；未安装时会失败）。

.EXAMPLE
    .\build_all.ps1
    .\build_all.ps1 -SkipTests
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Installer
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$distDir = Join-Path $PSScriptRoot 'dist'
$outputDir = Join-Path $PSScriptRoot 'build_windows'
$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'

if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "未找到 gradlew.bat，请确认在项目根目录执行。"
}

# 保证控制台以 UTF-8 输出，避免中文日志乱码
$OutputEncoding = [System.Text.Encoding]::UTF8

<#
    调用原生命令时必须临时关闭 Stop 模式：
    原生程序的 stderr 在 $ErrorActionPreference = 'Stop' 下会被 PowerShell 当作
    终止性错误抛出（例如 JVM 的 "Picked up JAVA_TOOL_OPTIONS" 提示），
    导致明明构建成功却报错退出。正确做法是只看退出码。
#>
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    # 安装包必须先单独跑完：releaseDist 是在 doLast 里从 build_windows/ 收集产物的，
    # 与 packageInstaller 放进同一次调用无法保证先后顺序
    if ($Installer) {
        & $gradlew ':app:packageInstaller' '--console=plain'
        if ($LASTEXITCODE -ne 0) {
            throw "安装包生成失败，退出码 $LASTEXITCODE"
        }
    }

    $tasks = @()
    if (-not $SkipTests) {
        $tasks += ':core:test'
    }
    $tasks += ':app:releaseDist'
    Write-Host "==> 执行 Gradle 任务: $($tasks -join ', ')" -ForegroundColor Cyan
    & $gradlew @tasks '--console=plain'
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}

if ($exitCode -ne 0) {
    throw "构建失败，退出码 $exitCode"
}

if (-not (Test-Path -LiteralPath $distDir)) {
    throw "构建完成但未找到 dist 目录: $distDir"
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
    Write-Host ("单文件发布包（对外分发只需这一个文件）: {0}" -f $portable.FullName) -ForegroundColor Green
}

$launcherExe = Join-Path $outputDir 'MisakaLauncher\MisakaLauncher.exe'
if (Test-Path -LiteralPath $launcherExe) {
    Write-Host ''
    Write-Host ("免安装版可直接运行（本地调试）: {0}" -f $launcherExe) -ForegroundColor Yellow
}
