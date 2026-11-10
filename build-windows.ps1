# Builds dist\LanLink\LanLink.exe (with its own Java runtime) and dist\LanLink-Windows.zip.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Run($exe, [string[]]$argv) {
    # Judge tools by exit code only: Java prints notices to stderr.
    $ErrorActionPreference = 'Continue'
    if (-not (Test-Path $exe)) { throw "Missing tool: $exe" }
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" }
}

$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    Get-ChildItem 'C:\Program Files\Java\jdk*', 'C:\Program Files\Eclipse Adoptium\jdk*' -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $jdk -or -not (Test-Path "$jdk\bin\jpackage.exe")) { throw "JDK 17+ (with jpackage) not found. Set JAVA_HOME." }
$javac = "$jdk\bin\javac.exe"; $jar = "$jdk\bin\jar.exe"; $java = "$jdk\bin\java.exe"; $jpackage = "$jdk\bin\jpackage.exe"

$out = "build\desktop"
Remove-Item -Recurse -Force $out, dist\LanLink, dist\LanLink-Windows.zip -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\jar", dist | Out-Null

Write-Host "1/3 Compile"
$src = @(Get-ChildItem core\src, desktop\src -Recurse -Filter *.java | ForEach-Object FullName)
Run $javac (@('-encoding', 'UTF-8', '--release', '17', '-d', "$out\classes") + $src)
Run $jar @('--create', '--file', "$out\jar\lanlink.jar", '--main-class', 'lanlink.desktop.Main', '-C', "$out\classes", '.')

Write-Host "2/3 Icon"
Run $java @('-cp', "$out\classes", 'lanlink.desktop.Icons', "$out\lanlink.ico")

Write-Host "3/3 Package exe"
Run $jpackage @('--type', 'app-image', '--name', 'LanLink', '--app-version', '1.0',
    '--vendor', 'LanLink', '--description', 'Use your phone and PC from each other over Wi-Fi',
    '--input', "$out\jar", '--main-jar', 'lanlink.jar', '--main-class', 'lanlink.desktop.Main',
    '--icon', "$out\lanlink.ico", '--dest', 'dist',
    '--add-modules', 'java.base,java.desktop,java.datatransfer,java.prefs',
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files --compress=2')
Compress-Archive -Path dist\LanLink -DestinationPath dist\LanLink-Windows.zip
Write-Host "Built dist\LanLink\LanLink.exe and dist\LanLink-Windows.zip"
