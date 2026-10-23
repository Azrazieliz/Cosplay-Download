$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$keys = Join-Path $env:USERPROFILE ".asterion-keys"
New-Item -ItemType Directory -Path $keys -Force | Out-Null
$keystore = Join-Path $keys "videoflow-release.jks"
$secret = Join-Path $keys "videoflow-release-password.dpapi"
if (-not (Test-Path $keystore)) {
    if (Test-Path $secret) { throw "Signing secret exists but keystore is missing; restore the key rather than replacing it." }
    $bytes = New-Object byte[] 40
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($bytes)
    $secure = ConvertTo-SecureString $password -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content $secret
    $keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    & $keytool -genkeypair -noprompt -keystore $keystore -alias videoflow -keyalg RSA -keysize 3072 -validity 10000 -storepass $password -keypass $password -dname "CN=VideoFlow, OU=Asterion, O=Personal Apps, C=FR"
    if ($LASTEXITCODE -ne 0) { Remove-Item $keystore -ErrorAction SilentlyContinue; throw "Key generation failed" }
    Write-Output "New persistent signing key created; preserve both files in $keys for future updates."
}
if (-not (Test-Path $secret)) { throw "Missing DPAPI secret; restore original signing credentials." }
$secure = Get-Content $secret | ConvertTo-SecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
$env:VIDEOFLOW_PASSWORD = $password
$env:VIDEOFLOW_KEYSTORE = $keystore
try {
    & (Join-Path $root "gradlew.bat") "-p" $root ":app:assembleRelease" "--no-daemon" "--console=plain"
    if ($LASTEXITCODE -ne 0) { throw "Android release build failed" }
} finally {
    Remove-Item Env:VIDEOFLOW_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:VIDEOFLOW_KEYSTORE -ErrorAction SilentlyContinue
    $password = $null
}
Write-Output "Signed release: $root\app\build\outputs\apk\release\app-release.apk"
