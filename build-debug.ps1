param([switch]$Offline)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'scripts\toolchain.ps1')
$taskArguments=@('verifyArchitecture',':core:domain:test',':core:agent:test',':integration:model:test',':data:local:testDebugUnitTest',':feature:ui:testDebugUnitTest',':app:testDebugUnitTest','lintDebug',':app:assembleDebug','--console=plain')
if($Offline) { $taskArguments+='--offline' }
& (Join-Path $PSScriptRoot 'gradlew.bat') @taskArguments
if($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
& (Join-Path $PSScriptRoot 'scripts\package-artifacts.ps1') -ProjectRoot $PSScriptRoot -Variant debug
