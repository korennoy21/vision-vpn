$ErrorActionPreference = 'Stop'
$severProject = $PSScriptRoot
$severVersion = '8.11.1'
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Install JDK 17 and add java to PATH, then run this script again.'
}
$severSdk = $env:ANDROID_HOME
if (-not $severSdk) { $severSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $severSdk 'platforms\android-35\android.jar'))) {
    throw 'Install Android SDK Platform 35 and Build Tools 35.0.0 in Android Studio SDK Manager.'
}
$env:ANDROID_HOME = $severSdk
$severCache = Join-Path $env:LOCALAPPDATA 'SeverBuild'
New-Item -ItemType Directory -Force -Path $severCache | Out-Null
$severGradle = Join-Path $severCache "gradle-$severVersion\bin\gradle.bat"
if (-not (Test-Path $severGradle)) {
    $severZip = Join-Path $severCache "gradle-$severVersion-bin.zip"
    $severUrl = "https://services.gradle.org/distributions/gradle-$severVersion-bin.zip"
    Write-Host 'Downloading Gradle from the official distribution service...'
    Invoke-WebRequest -Uri $severUrl -OutFile $severZip
    $severExpected = (Invoke-WebRequest -Uri "$severUrl.sha256").Content.Trim()
    if ($severExpected -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid Gradle checksum response' }
    $severActual = (Get-FileHash -Algorithm SHA256 -Path $severZip).Hash
    if ($severActual -ne $severExpected) { throw 'Gradle checksum mismatch; build stopped' }
    Expand-Archive -Path $severZip -DestinationPath $severCache -Force
}
& $severGradle -p $severProject wrapper --gradle-version $severVersion --distribution-type bin
if ($LASTEXITCODE -ne 0) { throw 'Gradle wrapper setup failed' }
& $severGradle -p $severProject assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
$severApk = Join-Path $severProject 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $severApk)) { throw 'Expected APK not found' }
Write-Host "APK created: $severApk"
