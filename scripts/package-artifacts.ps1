param([Parameter(Mandatory=$true)][string]$ProjectRoot,[Parameter(Mandatory=$true)][ValidateSet('debug','release')][string]$Variant)
$ErrorActionPreference='Stop'
$taskMetadata=Get-Content -LiteralPath (Join-Path $ProjectRoot "app\build\outputs\apk\$Variant\output-metadata.json") -Raw | ConvertFrom-Json
$taskVersion=$taskMetadata.elements[0].versionName
if($taskVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Unexpected APK version.' }
$taskSource=Join-Path $ProjectRoot "app\build\outputs\apk\$Variant\$($taskMetadata.elements[0].outputFile)"
$taskSigner=Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat'
$taskCertificate=@(& $taskSigner verify --print-certs $taskSource)
if($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$taskFingerprint=($taskCertificate | Select-String -Pattern '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)$').Matches.Groups[1].Value.ToLowerInvariant()
if($taskFingerprint.Length -ne 64) { throw 'Unable to identify the APK signing certificate.' }
$taskAapt=Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe'
$taskBadging=(@(& $taskAapt dump badging $taskSource) -join "`n")
if($LASTEXITCODE -ne 0 -or $taskBadging -notmatch ("package: name='io.github.ming.qingban' versionCode='$($taskMetadata.elements[0].versionCode)' versionName='"+[regex]::Escape($taskVersion)+"'")) { throw 'APK manifest version does not match build metadata.' }
$taskResources=(@(& $taskAapt dump resources $taskSource) -join "`n")
if($LASTEXITCODE -ne 0) { throw 'Unable to inspect packaged resources.' }
foreach($taskDrawable in @('gpt_icon','gpt_idle','gpt_sleep','gpt_thinking','gpt_waiting','gpt_welcome')) {
    if($taskResources -notmatch ('drawable/'+$taskDrawable+'\b')) { throw "Missing character resource: $taskDrawable" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip=[System.IO.Compression.ZipFile]::OpenRead($taskSource)
try {
    foreach($taskEntry in @('AndroidManifest.xml','classes.dex','agent-system.txt','assets/third-party-notices.md','assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf','assets/mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/Hani_ctc/optical/lstm_model.fb','lib/arm64-v8a/libmlkit_google_ocr_pipeline.so')) {
        if(-not $taskZip.GetEntry($taskEntry)) { throw "Missing runtime payload: $taskEntry" }
    }
    foreach($taskPayload in @(@{Entry='agent-system.txt';Source='core\agent\src\main\resources\agent-system.txt'},@{Entry='assets/third-party-notices.md';Source='docs\third-party-notices.md'})) {
        $taskStream=$taskZip.GetEntry($taskPayload.Entry).Open()
        $taskDigest=[System.Security.Cryptography.SHA256]::Create()
        try { $taskPayloadHash=([System.BitConverter]::ToString($taskDigest.ComputeHash($taskStream))).Replace('-','').ToLowerInvariant() } finally { $taskStream.Dispose(); $taskDigest.Dispose() }
        if($taskPayloadHash -ne (Get-FileHash -LiteralPath (Join-Path $ProjectRoot $taskPayload.Source) -Algorithm SHA256).Hash.ToLowerInvariant()) { throw "Packaged payload differs from source: $($taskPayload.Entry)" }
    }
} finally { $taskZip.Dispose() }
$taskDist=Join-Path $ProjectRoot 'dist'
New-Item -ItemType Directory -Path $taskDist -Force | Out-Null
$taskName="Qingban-$taskVersion-$Variant.apk"
$taskOutput=Join-Path $taskDist $taskName
Copy-Item -LiteralPath $taskSource -Destination $taskOutput
$taskHash=(Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$taskChecksumFile=Join-Path $taskDist 'SHA256SUMS.txt'
$taskLines=@()
if(Test-Path -LiteralPath $taskChecksumFile) { $taskLines=@(Get-Content -LiteralPath $taskChecksumFile | Where-Object { $_ -notmatch ([regex]::Escape($taskName)+'$') }) }
@($taskLines+($taskHash+'  '+$taskName)) | Set-Content -LiteralPath $taskChecksumFile -Encoding ascii
[ordered]@{artifact=$taskName;version=$taskVersion;versionCode=$taskMetadata.elements[0].versionCode;bytes=(Get-Item -LiteralPath $taskOutput).Length;sha256=$taskHash;signingCertificateSha256=$taskFingerprint;signatureVerified=$true;manifestVerified=$true;presetAndNoticesMatchSource=$true;characterAndOcrResourcesPresent=$true;verifiedAtUtc=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDist "$taskName.verification.json") -Encoding utf8
Write-Output "Verified artifact: $taskOutput"
