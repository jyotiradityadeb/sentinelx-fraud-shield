param(
    [string]$GradleVersion = "8.7"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$toolsDir = Join-Path (Split-Path -Parent $projectRoot) ".tools"
$gradleZip = Join-Path $toolsDir "gradle-$GradleVersion-bin.zip"
$gradleDir = Join-Path $toolsDir "gradle-$GradleVersion"
$gradleBat = Join-Path $gradleDir "bin\\gradle.bat"

$jbr = "C:\\Program Files\\Android\\Android Studio\\jbr"
if (Test-Path "$jbr\\bin\\java.exe") {
    $env:JAVA_HOME = $jbr
    $env:Path = "$jbr\\bin;$env:Path"
}

if (-not (Test-Path $gradleBat)) {
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
    if (-not (Test-Path $gradleZip)) {
        Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $gradleZip
    }
    Expand-Archive -Path $gradleZip -DestinationPath $toolsDir -Force
}

Set-Location $projectRoot

if (-not (Test-Path ".\\gradlew.bat")) {
    & $gradleBat wrapper --gradle-version $GradleVersion
}

if (-not (Test-Path ".\\local.properties")) {
    $defaultSdk = "C:\\Users\\jyoti\\AppData\\Local\\Android\\Sdk"
    if (Test-Path $defaultSdk) {
        Set-Content -Path ".\\local.properties" -Value "sdk.dir=C:\\Users\\jyoti\\AppData\\Local\\Android\\Sdk"
    }
}

.\\gradlew.bat --version
.\\gradlew.bat assembleDebug
.\\gradlew.bat testDebugUnitTest
.\\gradlew.bat connectedDebugAndroidTest
