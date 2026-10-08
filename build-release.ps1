param([switch]$Offline)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'scripts\toolchain.ps1')
$taskSigning=Join-Path $PSScriptRoot '.signing'
$taskProperties=Join-Path $taskSigning 'release.properties'
$taskKeystore=Join-Path $taskSigning 'qingban-release.jks'
if((Test-Path -LiteralPath $taskProperties) -ne (Test-Path -LiteralPath $taskKeystore)) { throw 'Signing files are incomplete. Restore the matching pair; do not generate a replacement key.' }
if(-not (Test-Path -LiteralPath $taskKeystore)) {
    New-Item -ItemType Directory -Path $taskSigning -Force | Out-Null
    $taskBytes=New-Object byte[] 32
    $taskRandom=[System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    $taskPassword=([System.BitConverter]::ToString($taskBytes)).Replace('-','').ToLowerInvariant()
    @('keyAlias=qingban',"storePassword=$taskPassword","keyPassword=$taskPassword") | Set-Content -LiteralPath $taskProperties -Encoding ascii
    $env:QINGBAN_SIGN_PASSWORD=$taskPassword
    try {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $taskKeystore -storetype JKS -alias qingban -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Qingban Local Release' -storepass:env QINGBAN_SIGN_PASSWORD -keypass:env QINGBAN_SIGN_PASSWORD -noprompt
        if($LASTEXITCODE -ne 0) { throw 'Unable to create the local release signing key.' }
    } finally { Remove-Item Env:QINGBAN_SIGN_PASSWORD -ErrorAction SilentlyContinue; $taskPassword=$null }
    if($env:OS -eq 'Windows_NT') {
        $taskIdentity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe $taskSigning /inheritance:r /grant:r "${taskIdentity}:(OI)(CI)F" | Out-Null
        if($LASTEXITCODE -ne 0) { throw 'Unable to restrict access to signing files.' }
    }
}
$taskArguments=@('verifyArchitecture',':core:domain:test',':core:agent:test',':integration:model:test',':data:local:testDebugUnitTest',':feature:ui:testDebugUnitTest',':app:testDebugUnitTest','lintDebug','lintRelease',':app:assembleDebug',':app:assembleRelease','--console=plain')
if($Offline) { $taskArguments+='--offline' }
& (Join-Path $PSScriptRoot 'gradlew.bat') @taskArguments
if($LASTEXITCODE -ne 0) { throw "Release build failed: $LASTEXITCODE" }
& (Join-Path $PSScriptRoot 'scripts\package-artifacts.ps1') -ProjectRoot $PSScriptRoot -Variant debug
& (Join-Path $PSScriptRoot 'scripts\package-artifacts.ps1') -ProjectRoot $PSScriptRoot -Variant release
