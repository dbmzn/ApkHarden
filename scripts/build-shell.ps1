# Compiles the Java shell and produces src/main/resources/shell.dex
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

# Resolve the Android SDK: ANDROID_HOME / ANDROID_SDK_ROOT, else local.properties sdk.dir.
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $lp = Join-Path $root "local.properties"
    if (Test-Path $lp) {
        $line = (Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir=' } | Select-Object -First 1)
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir=', '') -replace '\\\\', '\' }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) { throw "Android SDK not found. Set ANDROID_HOME or local.properties sdk.dir" }

$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName "android.jar"
$buildTools = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$d8 = Join-Path $buildTools.FullName "d8.bat"

Write-Host "SDK: $sdk"
Write-Host "android.jar: $androidJar"
Write-Host "d8: $d8"

$out = Join-Path $root "build\shell-classes"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null

$srcs = Get-ChildItem "$root\shell" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -cp $androidJar -d $out $srcs

$dexOut = Join-Path $root "build\shell-dex"
Remove-Item $dexOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dexOut | Out-Null
$classes = Get-ChildItem $out -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --min-api 26 --output $dexOut $classes --lib $androidJar

New-Item -ItemType Directory -Force (Join-Path $root "src\main\resources") | Out-Null
Copy-Item (Join-Path $dexOut "classes.dex") (Join-Path $root "src\main\resources\shell.dex") -Force
Write-Host "shell.dex written to src/main/resources/shell.dex"
