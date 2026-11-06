#requires -Version 7.0
<#
.SYNOPSIS
    P1A CarLife 现场验收一键驱动：定位 adb → 等待设备 → 安装 APK → 启动应用 →
    抓取 logcat → 调用 verify-carlife-log.ps1 输出 PASS/FAIL 摘要。

.DESCRIPTION
    设计目标：当手机 + 车机可用时，现场验收只需「一条命令 + 一次校验」：
        pwsh -NoProfile -File scripts/p1a-field-check.ps1
    之后按屏幕提示操作手机：1) CarLife+ 点无线连接 2) 应用连接车机 3) 保持 1–2 分钟。
    抓取结束后自动校验必需日志标记（见 docs/P1A-carlife-video.md §4 与
    「现场验收工具（P1A）」）并打印日志路径。

    本应用不内置无线 ADB 激活；USB 调试只用于「宿主电脑」侧 adb 安装 APK 和抓日志。
    当前没有设备时，脚本会在等待超时后给出明确提示并以非零码退出（不会打堆栈）。

    退出码:
        0  验收 PASS（日志包含全部必需标记且无失败标记）
        1  验收 FAIL（校验未通过）或运行时错误
        2  未找到可用设备 / adb 不可用
        3  检测到多台设备 / APK 文件不存在
        4  APK 安装失败
        5  应用启动失败
        6  logcat 抓取启动失败

.PARAMETER Apk
    APK 路径；相对路径依次相对「当前目录」「仓库根目录」解析。
    默认: app/build/outputs/apk/buick/app-buick.apk

.PARAMETER WaitDeviceSec
    等待手机（USB 调试已授权）的最长秒数，默认 120。

.PARAMETER LogDir
    日志输出目录；相对路径相对仓库根目录解析，默认 field-logs（已在 .gitignore 忽略）。

.PARAMETER NoInstall
    跳过 adb install -r（使用手机上已安装的版本）。

.PARAMETER DurationSec
    抓取 logcat 的秒数，默认 120；0 = 一直抓到按下 Ctrl+C。

.EXAMPLE
    pwsh -NoProfile -File scripts/p1a-field-check.ps1

.EXAMPLE
    pwsh -NoProfile -File scripts/p1a-field-check.ps1 -DurationSec 180 -WaitDeviceSec 30

.EXAMPLE
    pwsh -NoProfile -File scripts/p1a-field-check.ps1 -NoInstall -Apk app/build/outputs/apk/buick/app-buick.apk
#>
[CmdletBinding()]
param(
    [string]$Apk = 'app/build/outputs/apk/buick/app-buick.apk',
    [int]$WaitDeviceSec = 120,
    [string]$LogDir = 'field-logs',
    [switch]$NoInstall,
    [int]$DurationSec = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
# buick 变体：applicationIdSuffix=.buick，但 MainActivity 类名仍是 namespace 下的全限定名
$script:PackageName = 'io.github.shaonialife.buicklinkplus.buick'
$script:LaunchComponent = "$($script:PackageName)/io.github.shaonialife.buicklinkplus.MainActivity"
$script:LogTag = 'CarPlaySender'
$script:CaptureProcess = $null

# ---- 输出辅助 ----

function Write-Section {
    param([Parameter(Mandatory)][string]$Text)
    Write-Host ''
    Write-Host ("=== $Text ===") -ForegroundColor Cyan
}

function Write-Info {
    param([Parameter(Mandatory)][string]$Text)
    Write-Host ("  $Text")
}

function Write-Ok {
    param([Parameter(Mandatory)][string]$Text)
    Write-Host ("  [OK] $Text") -ForegroundColor Green
}

function Write-WarnLine {
    param([Parameter(Mandatory)][string]$Text)
    Write-Host ("  [WARN] $Text") -ForegroundColor Yellow
}

# 统一的失败出口：清空提示、非零退出，不打印 PowerShell 堆栈
function Stop-WithError {
    param(
        [Parameter(Mandatory)][int]$Code,
        [Parameter(Mandatory)][string]$Message,
        [string[]]$Hints = @()
    )
    Write-Host ''
    Write-Host ("[FAIL] $Message") -ForegroundColor Red
    foreach ($hint in $Hints) {
        Write-Host ("       $hint") -ForegroundColor Yellow
    }
    Write-Host ''
    exit $Code
}

# ---- adb / 设备 ----

function Resolve-AdbPath {
    $candidates = [System.Collections.Generic.List[string]]::new()
    $candidates.Add('F:\carplay\tools\android-sdk\platform-tools\adb.exe')
    if ($env:ANDROID_HOME) {
        $candidates.Add((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates.Add((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'))
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $onPath = Get-Command 'adb' -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

function Get-AdbDevices {
    param([Parameter(Mandatory)][string]$Adb)
    $devices = [System.Collections.Generic.List[object]]::new()
    try {
        $lines = & $Adb devices 2>$null
    } catch {
        return @()
    }
    foreach ($line in @($lines)) {
        $text = "$line".Trim()
        if ($text -match '^(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader)\s*$') {
            $devices.Add([pscustomobject]@{
                    Serial = $Matches[1]
                    State  = $Matches[2]
                })
        }
    }
    return @($devices)
}

function Wait-ForDevice {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][int]$TimeoutSec
    )

    $timeout = [Math]::Max(1, $TimeoutSec)
    $deadline = [DateTime]::UtcNow.AddSeconds($timeout)
    $notified = @{}
    Write-Info ("等待已授权设备（轮询 adb devices，最长 ${timeout}s）…")

    while ($true) {
        $devices = @(Get-AdbDevices -Adb $Adb)
        $ready = @($devices | Where-Object { $_.State -eq 'device' })
        $unauthorized = @($devices | Where-Object { $_.State -eq 'unauthorized' })
        $offline = @($devices | Where-Object { $_.State -eq 'offline' })

        if ($ready.Count -eq 1) { return $ready[0].Serial }
        if ($ready.Count -gt 1) {
            $serials = ($ready | ForEach-Object { $_.Serial }) -join ', '
            Stop-WithError -Code 3 -Message ("检测到多台已授权设备: $serials") -Hints @(
                '本脚本只支持单台手机；请拔掉多余的 USB 设备（并关闭无线调试连接）后重试。'
            )
        }
        if ($unauthorized.Count -gt 0 -and -not $notified.ContainsKey('unauthorized')) {
            $notified['unauthorized'] = $true
            Write-WarnLine ("设备已连接但未授权 USB 调试（$(($unauthorized | ForEach-Object { $_.Serial }) -join ', ')）：请在手机弹窗点「允许」。")
        }
        if ($offline.Count -gt 0 -and -not $notified.ContainsKey('offline')) {
            $notified['offline'] = $true
            Write-WarnLine ("设备处于 offline 状态（$(($offline | ForEach-Object { $_.Serial }) -join ', ')）：请重新插拔 USB 线。")
        }
        if (-not $notified.ContainsKey('waiting')) {
            $notified['waiting'] = $true
            Write-Info '尚未检测到设备，继续等待…（Ctrl+C 可中止）'
        }

        if ([DateTime]::UtcNow -ge $deadline) {
            Stop-WithError -Code 2 -Message ("未在 ${timeout}s 内检测到已授权的 Android 设备（adb: $Adb）") -Hints @(
                '1. 用支持数据传输的 USB 线连接手机，手机上选择「文件传输 / MTP」；',
                '2. 打开手机「设置 → 开发者选项 → USB 调试」（仅宿主 adb 需要，本应用不需要开发者选项/无线调试/ADB 激活）；',
                '3. 手机上确认「允许 USB 调试」弹窗，建议勾选「始终允许」；',
                ('4. 执行 "' + $Adb + ' devices"，成功时显示 "<序列号>    device"，显示 unauthorized 则重新授权；'),
                '5. 若换用其他机器，可设置 ANDROID_HOME 指向 Android SDK，或用 -WaitDeviceSec 调整等待时间。'
            )
        }
        Start-Sleep -Milliseconds 1000
    }
}

# ---- APK / 安装 / 启动 ----

function Resolve-ApkPath {
    param([Parameter(Mandatory)][string]$ApkParameter)
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ([System.IO.Path]::IsPathRooted($ApkParameter)) {
        $candidates.Add($ApkParameter)
    } else {
        $candidates.Add((Join-Path (Get-Location).Path $ApkParameter))
        $candidates.Add((Join-Path $script:RepoRoot $ApkParameter))
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Install-Apk {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$ApkPath
    )

    $size = (Get-Item -LiteralPath $ApkPath).Length
    Write-Info ("安装/覆盖安装: $ApkPath ($('{0:N0}' -f $size) B)")
    $output = & $Adb -s $Serial install -r $ApkPath 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    foreach ($line in ($output -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 })) {
        Write-Info ("  " + $line.Trim())
    }
    if ($exitCode -eq 0 -and $output -match 'Success') {
        Write-Ok 'APK 安装成功（-r 覆盖安装，保留应用数据）'
        return
    }

    $hints = [System.Collections.Generic.List[string]]::new()
    if ($output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        $hints.Add("包名已安装且签名不同。可先卸载再重试（会清除应用数据）: `"$Adb`" -s $Serial uninstall $($script:PackageName)")
    } elseif ($output -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
        $hints.Add("设备上的版本号更高。可先卸载再重试: `"$Adb`" -s $Serial uninstall $($script:PackageName)")
    } elseif ($output -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') {
        $hints.Add('手机存储空间不足，请清理后重试。')
    } elseif ($output -match 'no devices|device offline|device unauthorized') {
        $hints.Add('设备连接状态异常，请重新插拔 USB / 重新授权 USB 调试后再试。')
    }
    $hints.Add("排查后可单独执行: `"$Adb`" -s $Serial install -r `"$ApkPath`"")
    Stop-WithError -Code 4 -Message "APK 安装失败（adb 退出码 $exitCode）" -Hints @($hints)
}

function Start-App {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$Serial
    )

    Write-Info ("启动组件: $($script:LaunchComponent)")
    $output = & $Adb -s $Serial shell am start -W -n $script:LaunchComponent 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    foreach ($line in ($output -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 })) {
        Write-Info ("  " + $line.Trim())
    }
    if ($exitCode -ne 0 -or $output -match 'Error type|Exception|does not exist') {
        Stop-WithError -Code 5 -Message "应用启动失败（am start 退出码 $exitCode）" -Hints @(
            "1. 确认包已安装: `"$Adb`" -s $Serial shell pm list packages | Select-String buicklinkplus",
            '2. 确认手机已解锁；部分 ROM 限制后台启动 Activity，可在手机桌面手动点开「Buick Link＋」后重试；',
            "3. 也可以手动启动应用后使用 -NoInstall 再跑一次本脚本。"
        )
    }
    if ($output -notmatch 'Status:\s*ok') {
        Write-WarnLine '未看到 am start 的 "Status: ok"（部分 ROM 输出不同）；应用可能已在前台运行，继续抓取日志。'
    }
    Write-Ok '应用已启动（或已切到前台）'
}

function Show-OperatorSteps {
    Write-Host ''
    Write-Host '========== 请按以下顺序操作手机（抓取窗口保持运行）==========' -ForegroundColor Cyan
    Write-Host '  1) 手机上打开「CarLife+」，点「无线连接」（CarLife 引擎开始监听本机 127.0.0.1 端口）；' -ForegroundColor White
    Write-Host '  2) 回到本应用（Buick Link＋），按正常流程连接车机；' -ForegroundColor White
    Write-Host '  3) 保持 1–2 分钟：车机应出现 CarLife 画面，期间不要锁屏、不要退出应用。' -ForegroundColor White
    Write-Host ''
    Write-Host '  期望日志顺序: MODE: CarLife → CARLIFE: carlife connected → CARLIFE-VIDEO: decoder out → VIDEO: 帧' -ForegroundColor DarkGray
    Write-Host '  提示: 本应用无需开发者选项/无线调试/ADB 激活；USB 调试仅用于「宿主电脑」安装 APK 与抓 logcat。' -ForegroundColor DarkGray
    Write-Host '  抓取结束（或按 Ctrl+C）后会自动调用 verify-carlife-log.ps1 输出校验结果。' -ForegroundColor DarkGray
    Write-Host '==============================================================' -ForegroundColor Cyan
}

# ---- logcat 抓取 ----

function Read-NewLogLines {
    param(
        [Parameter(Mandatory)][string]$LogFile,
        [Parameter(Mandatory)][ref]$Offset,
        [switch]$Flush
    )

    try {
        $stream = [System.IO.File]::Open($LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    } catch {
        return @()
    }
    try {
        # 文件被重写时从头读
        if ($stream.Length -lt $Offset.Value) { $Offset.Value = [long]0 }
        if ($stream.Length -le $Offset.Value) { return @() }
        $length = [int]($stream.Length - $Offset.Value)
        $buffer = [byte[]]::new($length)
        $stream.Position = $Offset.Value
        $read = $stream.Read($buffer, 0, $length)
        if ($read -le 0) { return @() }

        # 只消费完整行，避免把 UTF-8 多字节字符/半行切坏；-Flush 时把末尾残留也当成一行
        $lastBreak = -1
        for ($i = $read - 1; $i -ge 0; $i--) {
            if ($buffer[$i] -eq 10) { $lastBreak = $i; break }
        }
        if ($lastBreak -lt 0 -and -not $Flush) { return @() }
        $consume = if ($lastBreak -ge 0) { $lastBreak + 1 } else { $read }
        $chunk = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $consume)
        $Offset.Value = $Offset.Value + $consume
        return @($chunk -split "`r?`n" | Where-Object { $_.Length -gt 0 })
    } finally {
        $stream.Dispose()
    }
}

function Test-ConsoleCtrlC {
    # 返回 true = 用户按下了 Ctrl+C；仅供开启 TreatControlCAsInput 后轮询
    try {
        if (-not [Console]::KeyAvailable) { return $false }
        $key = [Console]::ReadKey($true)
        return (($key.Modifiers -band [ConsoleModifiers]::Control) -and ($key.Key -eq [ConsoleKey]::C))
    } catch {
        return $false
    }
}

function Invoke-LogCapture {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$LogFile,
        [Parameter(Mandatory)][int]$Duration
    )

    $stderrFile = $LogFile + '.stderr'
    $adbArguments = "-s $Serial logcat -v time -s $($script:LogTag):V *:S"
    Write-Info ("抓取命令: `"$Adb`" $adbArguments")
    Write-Info ("日志文件:   $LogFile")
    if ($Duration -gt 0) {
        Write-Info ("抓取时长:   ${Duration}s（Ctrl+C 可提前结束并立即校验）")
    } else {
        Write-Info '抓取时长:   直到按下 Ctrl+C 为止'
    }

    try {
        $script:CaptureProcess = Start-Process -FilePath $Adb -ArgumentList $adbArguments `
            -RedirectStandardOutput $LogFile -RedirectStandardError $stderrFile -PassThru -WindowStyle Hidden
    } catch {
        Stop-WithError -Code 6 -Message ("启动 logcat 失败: " + $_.Exception.Message) -Hints @(
            "可手动执行: `"$Adb`" $adbArguments"
        )
    }
    $process = $script:CaptureProcess
    if ($null -eq $process) {
        Stop-WithError -Code 6 -Message '启动 logcat 失败（进程未创建）'
    }

    $offset = [long]0
    $startedAt = Get-Date
    $lineCount = 0
    $interrupted = $false
    $exitedEarly = $false
    $restoreCtrlC = $false
    $deadline = if ($Duration -gt 0) { $startedAt.AddSeconds($Duration) } else { $null }
    $nextProgressAt = $startedAt.AddSeconds(10)

    # 让 Ctrl+C 变成可轮询的输入，而不是直接终止脚本 —— 这样停止后仍能自动校验
    try {
        if (-not [Console]::IsInputRedirected) {
            [Console]::TreatControlCAsInput = $true
            $restoreCtrlC = $true
        }
    } catch {
        $restoreCtrlC = $false
    }

    try {
        while ($true) {
            foreach ($line in (Read-NewLogLines -LogFile $LogFile -Offset ([ref]$offset))) {
                $lineCount++
                Write-Host ("  $line")
            }
            if (Test-ConsoleCtrlC) {
                $interrupted = $true
                break
            }
            if ($process.HasExited) {
                $exitedEarly = $true
                break
            }
            if ($null -ne $deadline -and (Get-Date) -ge $deadline) { break }
            if ((Get-Date) -ge $nextProgressAt) {
                $elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
                Write-Info "…已抓取 ${elapsed}s / ${lineCount} 行"
                $nextProgressAt = $nextProgressAt.AddSeconds(10)
            }
            Start-Sleep -Milliseconds 250
        }
    } finally {
        if ($restoreCtrlC) {
            try { [Console]::TreatControlCAsInput = $false } catch { }
        }
        if (-not $process.HasExited) {
            try { $process.Kill() } catch { }
            try { $null = $process.WaitForExit(5000) } catch { }
        }
        Start-Sleep -Milliseconds 300
        foreach ($line in (Read-NewLogLines -LogFile $LogFile -Offset ([ref]$offset) -Flush)) {
            $lineCount++
            Write-Host ("  $line")
        }
        try { $process.Dispose() } catch { }
        $script:CaptureProcess = $null
    }

    Write-Host ''
    if ($interrupted) {
        Write-Info '已按 Ctrl+C 结束抓取（仍会对已抓到的日志执行校验）。'
    } elseif ($exitedEarly) {
        Write-WarnLine 'logcat 进程提前退出（设备可能断开或被其他 adb 命令占用）。'
    } else {
        Write-Info ("抓取完成：${Duration}s，共 ${lineCount} 行。")
    }

    if (Test-Path -LiteralPath $stderrFile) {
        $stderrText = (Get-Content -LiteralPath $stderrFile -Raw -Encoding utf8)
        if ([string]::IsNullOrWhiteSpace($stderrText)) {
            Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue
        } else {
            Write-WarnLine ("logcat stderr: " + $stderrText.Trim())
        }
    }
}

# ---- 主流程 ----

function Invoke-Main {
    Write-Host '==============================================================' -ForegroundColor Cyan
    Write-Host '  P1A CarLife 现场验收（安装 → 启动 → 抓日志 → 标记校验）' -ForegroundColor Cyan
    Write-Host '==============================================================' -ForegroundColor Cyan
    Write-Host ("仓库: $($script:RepoRoot)")
    Write-Host ("时间: " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))

    # 1) adb
    Write-Section '1/5 定位 adb'
    $adb = Resolve-AdbPath
    if (-not $adb) {
        Stop-WithError -Code 2 -Message '未找到 adb（Android SDK Platform-Tools）' -Hints @(
            '已尝试: F:\carplay\tools\android-sdk\platform-tools\adb.exe、$env:ANDROID_HOME\platform-tools\adb.exe、$env:ANDROID_SDK_ROOT、PATH',
            '请安装 Android SDK Platform-Tools，或设置 ANDROID_HOME 后重试。'
        )
    }
    Write-Ok ("adb: $adb")
    try {
        $adbVersion = (& $adb version 2>&1 | Select-Object -First 1).Trim()
        Write-Info $adbVersion
    } catch {
        Write-WarnLine 'adb version 未返回内容，继续尝试。'
    }

    # 2) 设备
    Write-Section "2/5 等待设备（最长 ${WaitDeviceSec}s）"
    $serial = Wait-ForDevice -Adb $adb -TimeoutSec $WaitDeviceSec
    Write-Ok ("设备: $serial")

    # 3) 安装
    Write-Section '3/5 安装 APK'
    if ($NoInstall) {
        Write-Info '已指定 -NoInstall，跳过 APK 安装（使用手机上已安装版本）。'
    } else {
        $apkPath = Resolve-ApkPath -ApkParameter $Apk
        if (-not $apkPath) {
            Stop-WithError -Code 3 -Message "APK 文件不存在: $Apk" -Hints @(
                '先构建: $env:JAVA_HOME=''F:\carplay\tools\jdk17\jdk-17.0.20.1''; .\gradlew.bat :app:assembleBuick',
                '或使用 -Apk 指定 APK 路径。'
            )
        }
        Install-Apk -Adb $adb -Serial $serial -ApkPath $apkPath
    }

    # 4) 清 logcat + 启动
    Write-Section '4/5 清空 logcat 并启动应用'
    $null = & $adb -s $serial logcat -c 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Ok 'logcat 已清空（只看本次现场操作产生的日志）'
    } else {
        Write-WarnLine "logcat -c 失败（退出码 $LASTEXITCODE），继续抓取（可能带旧日志）。"
    }
    Start-App -Adb $adb -Serial $serial
    Show-OperatorSteps

    # 5) 抓取 + 校验
    Write-Section '5/5 抓取日志并校验'
    $resolvedLogDir = if ([System.IO.Path]::IsPathRooted($LogDir)) { $LogDir } else { Join-Path $script:RepoRoot $LogDir }
    try {
        $null = New-Item -ItemType Directory -Path $resolvedLogDir -Force
    } catch {
        Stop-WithError -Code 1 -Message ("无法创建日志目录: $resolvedLogDir - " + $_.Exception.Message)
    }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $logFile = Join-Path $resolvedLogDir ("p1a-$stamp.log")
    Write-Info ("输出目录: $resolvedLogDir")

    Invoke-LogCapture -Adb $adb -Serial $serial -LogFile $logFile -Duration $DurationSec

    $logLineCount = 0
    if (Test-Path -LiteralPath $logFile) {
        $logLineCount = @(Get-Content -LiteralPath $logFile -Encoding utf8 -ErrorAction SilentlyContinue).Count
    }
    if ($logLineCount -eq 0) {
        Write-WarnLine '日志为空：确认抓取窗口内应用在运行、CarLife+ 已进入「无线连接」且车机已连接。'
    }

    # 用同一个 pwsh 调起校验器（独立进程，输出表格 + 退出码）
    Write-Section '日志标记校验'
    $verifier = Join-Path $PSScriptRoot 'verify-carlife-log.ps1'
    if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) {
        Stop-WithError -Code 1 -Message "缺少校验脚本: $verifier"
    }
    $pwshExe = Join-Path $PSHOME 'pwsh.exe'
    if (-not (Test-Path -LiteralPath $pwshExe -PathType Leaf)) { $pwshExe = 'pwsh' }
    & $pwshExe -NoProfile -File $verifier -Path $logFile
    $verifyExit = $LASTEXITCODE

    Write-Host ''
    Write-Host '================ P1A 现场验收摘要 ================' -ForegroundColor Cyan
    Write-Host ("设备:   $serial")
    Write-Host ("日志:   $logFile（$logLineCount 行）")
    if ($verifyExit -eq 0) {
        Write-Host '校验:   PASS（必需标记齐全，无失败标记）' -ForegroundColor Green
        Write-Host '结论:   本次现场验收通过；请把日志文件与结果记录到 docs/P0-baseline.md 待回填表。' -ForegroundColor Green
    } else {
        Write-Host "校验:   FAIL（退出码 $verifyExit）" -ForegroundColor Red
        Write-Host '结论:   未通过：查看上方缺失的必需标记 / 命中的失败标记；日志文件保留供排查。' -ForegroundColor Red
        Write-Host '       常见原因: CarLife+ 未进入「无线连接」、车机未连上、或未在抓取窗口内完成操作。' -ForegroundColor Yellow
    }
    Write-Host '==================================================' -ForegroundColor Cyan
    Write-Host ''

    if ($verifyExit -eq 0) {
        exit 0
    }
    exit 1
}

# ---- 入口（保证异常时输出友好提示，不打印堆栈）----
try {
    if ($WaitDeviceSec -lt 0) {
        Stop-WithError -Code 2 -Message '-WaitDeviceSec 不能为负数。'
    }
    if ($DurationSec -lt 0) {
        Stop-WithError -Code 2 -Message '-DurationSec 不能为负数；0 表示一直抓到 Ctrl+C。'
    }
    Invoke-Main
} catch {
    if ($null -ne $script:CaptureProcess -and -not $script:CaptureProcess.HasExited) {
        try { $script:CaptureProcess.Kill() } catch { }
    }
    Write-Host ''
    Write-Host ("[FAIL] 运行时错误: " + $_.Exception.Message) -ForegroundColor Red
    Write-Host '       可加 -Verbose 查看细节，或查看上文步骤定位问题。' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}
