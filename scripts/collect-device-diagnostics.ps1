param([string]$Serial, [string]$AdbPath)
$ErrorActionPreference='Stop'
$taskPackage='io.github.ming.qingban'
if(-not $AdbPath) {
    $taskCandidates=@()
    if($env:ANDROID_HOME) { $taskCandidates+=(Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    $taskCandidates+=(Join-Path $env:LOCALAPPDATA 'MingAlarmToolchain\sdk\platform-tools\adb.exe')
    $AdbPath=$taskCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if(-not $AdbPath) { $taskCommand=Get-Command adb -ErrorAction SilentlyContinue; if($taskCommand) { $AdbPath=$taskCommand.Source } }
}
if(-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) { throw 'ADB not found. Pass -AdbPath or configure ANDROID_HOME.' }
if(-not $Serial) {
    $taskDevices=@(& $AdbPath devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if($taskDevices.Count -ne 1) { throw 'Connect and authorize one phone, or pass -Serial when several devices are connected.' }
    $Serial=$taskDevices[0]
}
function Invoke-TaskAdb([string[]]$Arguments) {
    $taskOutput=& $AdbPath -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -eq 1 -and ($Arguments -join ' ') -eq "shell pidof $taskPackage") { return @() }
    if($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' '): $($taskOutput -join [Environment]::NewLine)" }
    return @($taskOutput | ForEach-Object { "$_" })
}
if((Invoke-TaskAdb @('get-state')) -ne 'device') { throw 'The phone is not ready.' }
$taskDirectory=Join-Path (Split-Path -Parent $PSScriptRoot) ('dist\device-debug\diagnostics-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
$taskInfo=[ordered]@{
    capturedAtUtc=[DateTime]::UtcNow.ToString('o')
    package=$taskPackage
    model=(Invoke-TaskAdb @('shell','getprop','ro.product.model')) -join ''
    android=(Invoke-TaskAdb @('shell','getprop','ro.build.version.release')) -join ''
    api=(Invoke-TaskAdb @('shell','getprop','ro.build.version.sdk')) -join ''
    display=(Invoke-TaskAdb @('shell','wm','size')) -join ' '
    density=(Invoke-TaskAdb @('shell','wm','density')) -join ' '
}
$taskPackageDump=Invoke-TaskAdb @('shell','dumpsys','package',$taskPackage)
$taskInfo.version=($taskPackageDump | Where-Object { $_ -match 'versionName=|versionCode=' } | ForEach-Object { $_.Trim() }) -join '; '
$taskInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDirectory 'device.json') -Encoding utf8
$taskPermissions=@($taskPackageDump | Where-Object { $_ -match 'android.permission.(POST_NOTIFICATIONS|SCHEDULE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW).*granted=' })
foreach($taskOp in @('POST_NOTIFICATION','SCHEDULE_EXACT_ALARM','USE_FULL_SCREEN_INTENT','SYSTEM_ALERT_WINDOW')) {
    $taskPermissions+=Invoke-TaskAdb @('shell','appops','get',$taskPackage,$taskOp)
}
$taskWhitelist=Invoke-TaskAdb @('shell','dumpsys','deviceidle','whitelist')
$taskPermissions+=('Battery whitelist match: '+(($taskWhitelist | Where-Object { $_ -match [regex]::Escape($taskPackage) }) -join ', '))
$taskPermissions | Set-Content -LiteralPath (Join-Path $taskDirectory 'permissions.txt') -Encoding utf8
Invoke-TaskAdb @('shell','dumpsys','activity','exit-info',$taskPackage) | Set-Content -LiteralPath (Join-Path $taskDirectory 'process-exits.txt') -Encoding utf8
$taskServices=Invoke-TaskAdb @('shell','dumpsys','activity','services',$taskPackage)
$taskServices | Where-Object { $_ -match 'ServiceRecord|isForeground=|startRequested=|foregroundId=' } | Set-Content -LiteralPath (Join-Path $taskDirectory 'services.txt') -Encoding utf8
$taskPid=(Invoke-TaskAdb @('shell','pidof',$taskPackage)) -join ''
if($taskPid -match '^\d+$') {
    Invoke-TaskAdb @('logcat','-d','-b','main','-b','system','-b','crash','-t','500',"--pid=$taskPid",'-v','threadtime','*:E') | Set-Content -LiteralPath (Join-Path $taskDirectory 'app-errors.txt') -Encoding utf8
} else {
    'App is not running. See process-exits.txt; no logcat from other apps was collected.' | Set-Content -LiteralPath (Join-Path $taskDirectory 'app-errors.txt') -Encoding utf8
}
@'
Read-only capture: no screenshots, UI text, API configuration, app files, or logs from other app processes.
No log buffers cleared, permissions changed, app restarts, model calls, or data resets.
app-errors.txt contains only the current process; earlier process exits are in process-exits.txt.
When reporting a problem, add reproduction steps, expected/actual results, and the phone's local time.
These files stay on this computer and are not uploaded automatically.
'@ | Set-Content -LiteralPath (Join-Path $taskDirectory 'README.txt') -Encoding utf8
Write-Output "Diagnostics saved: $taskDirectory"
