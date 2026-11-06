#requires -Version 7.0
<#
.SYNOPSIS
    P1A CarLife 现场日志标记校验器（无需手机/车机）。

.DESCRIPTION
    校验 `adb logcat -v time -s CarPlaySender:V *:S` 抓到的 P1A 日志是否包含验收标记。
    标记字符串取自生产日志（docs/P1A-carlife-video.md §4）：
      必需（全部出现才算 PASS）:
        MODE: CarLife / CARLIFE: carlife connected /
        CARLIFE-VIDEO: decoder out / VIDEO: 帧
      建议（仅提示，不影响退出码）:
        CARLIFE-VIDEO: fit / CARLIFE: …重连|断开 / AIRPLAY:
      失败（任一命中即 FAIL）:
        编码器取帧异常 / CARLIFE-VIDEO: 解码器错误 /
        解码线程异常退出 / 重建解码器仍失败
    匹配区分大小写，按子串（部分为 .NET 正则）判断；输出逐项表格与总结。
    退出码：0 = 必需标记全通过且无失败标记；1 = 校验 FAIL；2 = 参数/读取错误。
    -Json 仅向 stdout 输出机器可读结果；-SelfTest 用合成日志自检，无需设备。

.PARAMETER Path
    日志文件路径（UTF-8 文本，通常为 field-logs/p1a-*.log）。-SelfTest 时可省略。

.PARAMETER Json
    只输出 JSON 结果（含逐项明细与退出码），便于脚本/CI 消费。

.PARAMETER SelfTest
    自检：在临时目录生成 3 份合成日志（好日志 / 缺必需标记 / 含失败标记），
    验证本工具分别返回 PASS / FAIL / FAIL，全部符合预期时打印 SelfTest OK 并退出 0。

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-carlife-log.ps1 -Path field-logs/p1a-20261106-210000.log

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-carlife-log.ps1 -Path field-logs/p1a-20261106-210000.log -Json

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-carlife-log.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$Path,
    [switch]$Json,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---- 标记定义（区分大小写；字符串与生产代码一一对应，改动前先 grep 源码）----
$script:RequiredChecks = @(
    [pscustomobject]@{
        Marker = 'MODE: CarLife'
        Kind   = 'Substring'
        Note   = '会话画面来源已切换为 CarLife（SessionController）'
    },
    [pscustomobject]@{
        Marker = 'CARLIFE: carlife connected'
        Kind   = 'Substring'
        Note   = '已连上本机 CarLife+ 引擎并握手成功（CarLifeScreenSource）'
    },
    [pscustomobject]@{
        Marker = 'CARLIFE-VIDEO: decoder out'
        Kind   = 'Substring'
        Note   = 'MediaCodec 解码器开始输出帧（首帧/每 150 帧）'
    },
    [pscustomobject]@{
        Marker = 'VIDEO: 帧'
        Kind   = 'Substring'
        Note   = 'VideoSender 已把编码帧写入 AirPlay 车机流'
    }
)

$script:AdvisoryChecks = @(
    [pscustomobject]@{
        Marker = 'CARLIFE-VIDEO: fit'
        Kind   = 'Substring'
        Note   = '码流尺寸与编码器尺寸关系（尺寸不一致时 P1A 会用 fill 拉伸）'
    },
    [pscustomobject]@{
        Marker = 'CARLIFE: .*(重连|断开)'
        Kind   = 'Regex'
        Note   = '断线重连/断开收尾（仅涉及断开重连场景，未出现属正常）'
    },
    [pscustomobject]@{
        Marker = 'AIRPLAY: '
        Kind   = 'Substring'
        Note   = 'AirPlay 会话链路日志（SETUP/流协商/RECORD）'
    }
)

$script:FailureChecks = @(
    [pscustomobject]@{
        Marker = '编码器取帧异常'
        Kind   = 'Substring'
        Note   = 'VideoSender 编码器取帧失败，推流已停止'
    },
    [pscustomobject]@{
        Marker = 'CARLIFE-VIDEO: 解码器错误'
        Kind   = 'Substring'
        Note   = 'CarLifeVideoDecoder onDecoderError 落日志（解码/codec 异常）'
    },
    [pscustomobject]@{
        Marker = '解码线程异常退出'
        Kind   = 'Substring'
        Note   = '解码线程致命异常退出'
    },
    [pscustomobject]@{
        Marker = '重建解码器仍失败'
        Kind   = 'Substring'
        Note   = '连续重建解码器失败后放弃（致命）'
    }
)

# ---- 核心判定 ----

# 单条标记扫描：返回首个命中行号、命中行数与首次命中文本；整行只扫描一遍。
function Find-MarkerOccurrence {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Lines,
        [Parameter(Mandatory)][pscustomobject]$Check
    )

    $count = 0
    $firstLine = $null
    $firstText = ''
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $line = $Lines[$i]
        $hit = if ($Check.Kind -eq 'Regex') {
            [regex]::IsMatch($line, $Check.Marker)
        } else {
            $line.IndexOf($Check.Marker, [System.StringComparison]::Ordinal) -ge 0
        }
        if (-not $hit) { continue }
        $count++
        if ($null -eq $firstLine) {
            $firstLine = $i + 1
            $firstText = $line.Trim()
        }
    }
    [pscustomobject]@{
        Hit        = $count -gt 0
        Count      = $count
        LineNumber = $firstLine
        LineText   = $firstText
    }
}

# 纯判定函数（不读文件）：输入日志行，返回完整校验报告对象。
function Test-CarLifeLog {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Lines,
        [string]$LogPath = ''
    )

    $required = @(
        foreach ($check in $script:RequiredChecks) {
            $occurrence = Find-MarkerOccurrence -Lines $Lines -Check $check
            [pscustomobject]@{
                Type       = 'REQUIRED'
                Marker     = $check.Marker
                Status     = if ($occurrence.Hit) { 'PASS' } else { 'FAIL' }
                Hit        = $occurrence.Hit
                Count      = $occurrence.Count
                Line       = $occurrence.LineNumber
                LineText   = $occurrence.LineText
                Note       = $check.Note
            }
        }
    )

    $advisory = @(
        foreach ($check in $script:AdvisoryChecks) {
            $occurrence = Find-MarkerOccurrence -Lines $Lines -Check $check
            [pscustomobject]@{
                Type       = 'ADVISORY'
                Marker     = $check.Marker
                Status     = 'ADVISORY'
                Hit        = $occurrence.Hit
                Count      = $occurrence.Count
                Line       = $occurrence.LineNumber
                LineText   = $occurrence.LineText
                Note       = $check.Note
            }
        }
    )

    $failure = @(
        foreach ($check in $script:FailureChecks) {
            $occurrence = Find-MarkerOccurrence -Lines $Lines -Check $check
            [pscustomobject]@{
                Type       = 'FAILURE'
                Marker     = $check.Marker
                Status     = if ($occurrence.Hit) { 'FAIL' } else { 'PASS' }
                Hit        = $occurrence.Hit
                Count      = $occurrence.Count
                Line       = $occurrence.LineNumber
                LineText   = $occurrence.LineText
                Note       = $check.Note
            }
        }
    )

    $requiredFailed = @($required | Where-Object { $_.Status -ne 'PASS' })
    $failureHit = @($failure | Where-Object { $_.Hit })
    $advisoryHit = @($advisory | Where-Object { $_.Hit })
    $ok = ($requiredFailed.Count -eq 0) -and ($failureHit.Count -eq 0)

    $parts = [System.Collections.Generic.List[string]]::new()
    if ($requiredFailed.Count -eq 0) {
        $parts.Add('必需标记 {0}/{0} 通过' -f $required.Count)
    } else {
        $parts.Add('缺少必需标记: ' + (($requiredFailed | ForEach-Object { $_.Marker }) -join ' / '))
    }
    if ($failureHit.Count -eq 0) {
        $parts.Add('未命中失败标记')
    } else {
        $parts.Add('命中失败标记: ' + (($failureHit | ForEach-Object { $_.Marker }) -join ' / '))
    }
    if ($Lines.Count -eq 0) {
        $parts.Add('日志为空：请确认抓取窗口内应用在运行、CarLife+ 已进入「无线连接」')
    }

    [pscustomobject]@{
        Path        = $LogPath
        CheckedAt   = (Get-Date).ToString('o')
        LineCount   = $Lines.Count
        Result      = if ($ok) { 'PASS' } else { 'FAIL' }
        ExitCode    = if ($ok) { 0 } else { 1 }
        Required    = $required
        Advisory    = $advisory
        Failure     = $failure
        Summary     = [pscustomobject]@{
            RequiredPassed = $required.Count - $requiredFailed.Count
            RequiredTotal  = $required.Count
            FailureHits    = $failureHit.Count
            FailureTotal   = $failure.Count
            AdvisoryHits   = $advisoryHit.Count
            AdvisoryTotal  = $advisory.Count
            Detail         = ($parts -join '；')
        }
    }
}

# ---- 控制台输出 ----

function Write-MarkerTable {
    param([Parameter(Mandatory)][pscustomobject]$Report)

    Write-Host ''
    Write-Host '================ P1A CarLife 日志标记校验 ================' -ForegroundColor Cyan
    Write-Host ('日志文件: ' + $(if ($Report.Path) { $Report.Path } else { '(合成日志)' }))
    Write-Host ('行数:     ' + $Report.LineCount + '    校验时间: ' + $Report.CheckedAt)
    Write-Host ''
    Write-Host ('{0,-10} {1,-10} {2,-7} {3,-6} {4}' -f '类型', '判定', '行号', '次数', '标记')
    Write-Host ('{0,-10} {1,-10} {2,-7} {3,-6} {4}' -f '------', '------', '----', '----', '----')

    foreach ($row in @($Report.Required) + @($Report.Advisory) + @($Report.Failure)) {
        $lineText = if ($null -ne $row.Line) { "$($row.Line)" } else { '-' }
        $countText = if ($row.Hit) { "$($row.Count)" } else { '0' }
        $color = switch ($row.Status) {
            'PASS' { 'Green' }
            'FAIL' { 'Red' }
            default { 'Yellow' }
        }
        Write-Host ('{0,-10} {1,-10} {2,-7} {3,-6} {4}' -f $row.Type, $row.Status, $lineText, $countText, $row.Marker) -ForegroundColor $color
    }

    Write-Host ''
    $summary = $Report.Summary
    Write-Host ('必需标记: ' + $summary.RequiredPassed + '/' + $summary.RequiredTotal + ' 通过')
    Write-Host ('失败标记: ' + $summary.FailureHits + '/' + $summary.FailureTotal + ' 命中（命中即 FAIL）')
    Write-Host ('建议标记: ' + $summary.AdvisoryHits + '/' + $summary.AdvisoryTotal + ' 出现（仅提示，不影响判定）')

    $failureRows = @($Report.Failure | Where-Object { $_.Hit })
    if ($failureRows.Count -gt 0) {
        Write-Host ''
        Write-Host '失败明细:' -ForegroundColor Red
        foreach ($row in $failureRows) {
            $text = $row.LineText
            if ($text.Length -gt 160) { $text = $text.Substring(0, 160) + '…' }
            Write-Host ('  - [行 ' + $row.Line + '] ' + $text) -ForegroundColor Red
        }
    }

    Write-Host ''
    if ($Report.Result -eq 'PASS') {
        Write-Host ('结论: PASS —— ' + $summary.Detail) -ForegroundColor Green
    } else {
        Write-Host ('结论: FAIL —— ' + $summary.Detail) -ForegroundColor Red
        Write-Host '提示: 现场操作顺序须在抓日志窗口内完成；详见 docs/P1A-carlife-video.md「现场验收工具（P1A）」' -ForegroundColor Yellow
    }
}

# ---- -SelfTest：用合成日志验证本工具自身的判定逻辑 ----

function Invoke-SelfTest {
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ('p1a-verify-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    # 与生产日志同构的行（logcat -v time 格式 + CarPlaySender 标签 + 真实日志文本）
    $goodLines = @(
        '11-06 21:00:00.101  4321  4400 I CarPlaySender: AIRPLAY: SETUP 应答 [200 OK]（自报 deviceID=xx:xx:xx:xx:xx:xx）'
        '11-06 21:00:00.210  4321  4400 I CarPlaySender: AIRPLAY: ✓ 主屏流协商成功 dataPort=49152 connId=102'
        '11-06 21:00:00.350  4321  4460 I CarPlaySender: MODE: CarLife 唯一内容源 已启动（等待 CarLife+ 进入无线连接），请求 1920x1080@30fps'
        '11-06 21:00:00.351  4321  4470 I CarPlaySender: CARLIFE: 内容源已挂载 1920x1080@30fps density=420 pkg=io.github.shaonialife.buicklinkplus.buick（等待 CarLife+ 进入无线连接）'
        '11-06 21:00:00.352  4321  4480 I CarPlaySender: CARLIFE: 管理线程启动（等待 CarLife+ 打开无线连接）'
        '11-06 21:00:03.010  4321  4480 I CarPlaySender: CARLIFE: carlife 连接失败，2s 后重试（请确认手机 CarLife+ 已进入「无线连接」）'
        '11-06 21:00:05.100  4321  4480 I CarPlaySender: CARLIFE: carlife connected 127.0.0.1（握手成功，等待视频帧）'
        '11-06 21:00:05.240  4321  4480 I CarPlaySender: CARLIFE: 协商编码参数 1280x720@30fps'
        '11-06 21:00:05.250  4321  4480 I CarPlaySender: CARLIFE: 客户端 SPS 实测 1280x720'
        '11-06 21:00:05.260  4321  4480 I CarPlaySender: CARLIFE-VIDEO: 码流真实尺寸 1280x720（编码器 1920x1080）'
        '11-06 21:00:05.400  4321  4490 I CarPlaySender: CARLIFE-VIDEO: 解码器已启动 stream=1280x720 encoder=1920x1080 target=30fps'
        '11-06 21:00:05.401  4321  4490 I CarPlaySender: CARLIFE-VIDEO: fit stream=1280x720 encoder=1920x1080 scale=1.5x1.5；尺寸不一致：直出到编码器 Surface 会按 fill 拉伸（P1B 合成器再做 contain 修正）'
        '11-06 21:00:05.460  4321  4490 I CarPlaySender: CARLIFE-VIDEO: decoder out #1 pts=0ms stream=1280x720 encoder=1920x1080'
        '11-06 21:00:05.470  4321  4490 I CarPlaySender: VIDEO: 帧 #1 15360B (key) 已发送'
        '11-06 21:00:07.900  4321  4480 I CarPlaySender: CARLIFE: 连接已断开，收尾解码器/管线后 2s 重连'
        '11-06 21:00:09.950  4321  4480 I CarPlaySender: CARLIFE: carlife connected 127.0.0.1（握手成功，等待视频帧）'
        '11-06 21:00:12.120  4321  4490 I CarPlaySender: CARLIFE-VIDEO: decoder out #150 pts=5010ms stream=1280x720 encoder=1920x1080'
        '11-06 21:00:12.130  4321  4490 I CarPlaySender: VIDEO: 帧 #150 20480B 已发送'
    )

    # 缺必需标记：去掉唯一一条 “CARLIFE-VIDEO: decoder out”
    $missingRequiredLines = @($goodLines | Where-Object { $_ -notmatch 'CARLIFE-VIDEO: decoder out' })

    # 含失败标记：保留全部必需标记，再追加编码器取帧异常（生产原文）
    $failureLines = @(
        $goodLines
        '11-06 21:00:20.300  4321  4520 I CarPlaySender: VIDEO: 编码器取帧异常，停止推流 - IllegalStateException: codec in error state'
    )

    $cases = @(
        [pscustomobject]@{ Name = '好日志（标记齐全）'; Expect = 'PASS'; Lines = $goodLines },
        [pscustomobject]@{ Name = '缺必需标记 decoder out'; Expect = 'FAIL'; Lines = $missingRequiredLines },
        [pscustomobject]@{ Name = '含失败标记 编码器取帧异常'; Expect = 'FAIL'; Lines = $failureLines }
    )

    $allOk = $true
    $index = 0
    try {
        foreach ($case in $cases) {
            $index++
            $file = Join-Path $tempDir ('case_{0}.log' -f $index)
            [System.IO.File]::WriteAllText($file, (($case.Lines -join "`r`n") + "`r`n"), [System.Text.UTF8Encoding]::new($false))
            $lines = @(Get-Content -LiteralPath $file -Encoding utf8)
            $report = Test-CarLifeLog -Lines $lines -LogPath $file
            $expectedExit = if ($case.Expect -eq 'PASS') { 0 } else { 1 }
            $caseOk = ($report.Result -eq $case.Expect) -and ($report.ExitCode -eq $expectedExit)
            if (-not $caseOk) { $allOk = $false }
            $verdict = if ($caseOk) { 'OK' } else { '不符合预期' }
            $color = if ($caseOk) { 'Green' } else { 'Red' }
            Write-Host ('[{0}/{1}] {2}: 实际 {3}（退出码 {4}），期望 {5} -> {6}' -f `
                    $index, $cases.Count, $case.Name, $report.Result, $report.ExitCode, $case.Expect, $verdict) -ForegroundColor $color
        }
    } finally {
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host ''
    if ($allOk) {
        Write-Host 'SelfTest OK（3 份合成日志行为均符合预期：PASS / FAIL / FAIL）' -ForegroundColor Green
        return 0
    }
    Write-Host 'SelfTest FAILED：校验器判定逻辑与预期不符，勿用于现场验收' -ForegroundColor Red
    return 1
}

# ---- 主流程 ----

if ($SelfTest) {
    Write-Host '=== verify-carlife-log.ps1 自检（不需要设备）===' -ForegroundColor Cyan
    exit (Invoke-SelfTest)
}

if ([string]::IsNullOrWhiteSpace($Path)) {
    Write-Host '错误: 缺少 -Path 参数（或使用 -SelfTest 自检）。' -ForegroundColor Red
    Write-Host '用法: pwsh -NoProfile -File scripts/verify-carlife-log.ps1 -Path field-logs/p1a-<时间戳>.log [-Json]' -ForegroundColor Yellow
    exit 2
}

$resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
if ($null -eq $resolved) {
    Write-Host ('错误: 日志文件不存在: ' + $Path) -ForegroundColor Red
    Write-Host '用法: pwsh -NoProfile -File scripts/verify-carlife-log.ps1 -Path <adb logcat 抓取文件> [-Json]' -ForegroundColor Yellow
    exit 2
}

$logPath = $resolved.Path
try {
    $lines = @(Get-Content -LiteralPath $logPath -Encoding utf8)
} catch {
    Write-Host ('错误: 读取日志失败: ' + $_.Exception.Message) -ForegroundColor Red
    exit 2
}

$report = Test-CarLifeLog -Lines $lines -LogPath $logPath

if ($Json) {
    $report | ConvertTo-Json -Depth 6
} else {
    Write-MarkerTable -Report $report
}

exit $report.ExitCode
