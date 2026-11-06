# Environment check for `linkplus_carlife`
# Run from repo root with JDK17 available.

$ErrorActionPreference = 'Stop'

Write-Host "=== link_plus CarLife port environment ===" -ForegroundColor Cyan

$jdk17 = 'F:\carplay\tools\jdk17\jdk-17.0.20.1'
if (Test-Path (Join-Path $jdk17 'bin\java.exe')) {
    Write-Host "JDK17: $jdk17" -ForegroundColor Green
    & (Join-Path $jdk17 'bin\java.exe') -version 2>&1 | ForEach-Object { "  $_" }
} else {
    Write-Host "JDK17 MISSING: $jdk17" -ForegroundColor Red
}

$sdk = 'F:\carplay\tools\android-sdk'
Write-Host "SDK: $sdk"
$required = @(
    'platforms\android-34',
    'build-tools\35.0.0',
    'ndk\27.0.12077973'
)
foreach ($item in $required) {
    $path = Join-Path $sdk $item
    if (Test-Path $path) {
        Write-Host "  OK   $item" -ForegroundColor Green
    } else {
        Write-Host "  MISS $item" -ForegroundColor Yellow
    }
}

$localProps = Join-Path $PSScriptRoot '..\local.properties'
if (-not (Test-Path $localProps)) {
    Write-Host "local.properties: missing (create with sdk.dir)" -ForegroundColor Yellow
} else {
    Write-Host "local.properties:" -ForegroundColor Green
    Get-Content $localProps | ForEach-Object { "  $_" }
}

Write-Host ""
Write-Host "Install missing SDK parts with:"
Write-Host "  `$env:JAVA_HOME='$jdk17'"
Write-Host "  & '$sdk\cmdline-tools\latest\bin\sdkmanager.bat' 'platforms;android-34' 'build-tools;35.0.0' 'ndk;27.0.12077973'"
Write-Host ""
Write-Host "Gradle build:"
Write-Host "  `$env:JAVA_HOME='$jdk17'"
Write-Host "  .\gradlew.bat :app:assembleBuick"
