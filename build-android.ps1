# Builds dist\LanLink.apk with the Android SDK command-line tools (no Gradle needed).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" | Sort-Object { [version]$_.Name } | Select-Object -Last 1 -ExpandProperty FullName
$androidJar = "$sdk\platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) { throw "Install 'Android SDK Platform 34' in Android Studio's SDK Manager." }

$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    Get-ChildItem 'C:\Program Files\Java\jdk*', 'C:\Program Files\Eclipse Adoptium\jdk*' -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $jdk -or -not (Test-Path "$jdk\bin\jar.exe")) { throw "JDK 17+ not found. Set JAVA_HOME." }
$javac = "$jdk\bin\javac.exe"; $jar = "$jdk\bin\jar.exe"; $keytool = "$jdk\bin\keytool.exe"

$out = "build\android"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex", dist | Out-Null

function Run($exe, [string[]]$argv) {
    # Judge tools by exit code only: Java prints notices to stderr.
    $ErrorActionPreference = 'Continue'
    if (-not (Test-Path $exe)) { throw "Missing tool: $exe" }
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" }
}

Write-Host "1/5 Resources"
Run "$bt\aapt2.exe" @('compile', '--dir', 'android\res', '-o', "$out\res.zip")
Run "$bt\aapt2.exe" @('link', '-o', "$out\base.apk", '-I', $androidJar,
    '--manifest', 'android\AndroidManifest.xml', '--java', "$out\gen",
    '--min-sdk-version', '24', '--target-sdk-version', '34',
    '--version-code', '1', '--version-name', '1.0', "$out\res.zip")

Write-Host "2/5 Compile Java"
$src = @(Get-ChildItem core\src, android\src, "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run $javac (@('-nowarn', '-Xlint:-options', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-bootclasspath', "$androidJar;$bt\core-lambda-stubs.jar", '-d', "$out\classes") + $src)

Write-Host "3/5 Dex"
Run $jar @('--create', '--file', "$out\classes.jar", '-C', "$out\classes", '.')
Run "$bt\d8.bat" @('--release', '--min-api', '24', '--lib', $androidJar, '--output', "$out\dex", "$out\classes.jar")

Write-Host "4/5 Package"
Push-Location "$out\dex"
Run "$bt\aapt.exe" @('add', '..\base.apk', 'classes.dex')
Pop-Location
Run "$bt\zipalign.exe" @('-f', '-p', '4', "$out\base.apk", "$out\aligned.apk")

Write-Host "5/5 Sign"
# A fixed keystore, so new builds install over old ones. Keep this file.
$ks = 'android\lanlink.keystore'
if (-not (Test-Path $ks)) {
    Run $keytool @('-genkeypair', '-keystore', $ks, '-alias', 'lanlink', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-storepass', 'lanlink', '-keypass', 'lanlink', '-dname', 'CN=LanLink')
}
Run "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-pass', 'pass:lanlink', '--key-pass', 'pass:lanlink',
    '--out', 'dist\LanLink.apk', "$out\aligned.apk")
Run "$bt\apksigner.bat" @('verify', 'dist\LanLink.apk')
Write-Host "Built dist\LanLink.apk"
