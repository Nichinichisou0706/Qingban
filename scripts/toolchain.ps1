$taskToolchain = Join-Path $env:LOCALAPPDATA 'MingAlarmToolchain'
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $taskToolchain 'jdk'))) {
    $taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskToolchain 'jdk') -Directory | Select-Object -First 1
    $env:JAVA_HOME = $taskJdk.FullName
}
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $taskToolchain 'sdk'))) { $env:ANDROID_HOME = Join-Path $taskToolchain 'sdk' }
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Set JAVA_HOME and ANDROID_HOME to a JDK 17 and Android SDK 35.' }
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
