# Compiles the static guard and produces src/main/resources/guard.dex.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

# Resolve the Android SDK: ANDROID_HOME / ANDROID_SDK_ROOT, else local.properties sdk.dir.
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $lp = Join-Path $root "local.properties"
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    throw "Android SDK not found. Set ANDROID_HOME or local.properties sdk.dir"
}

$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName "android.jar"
$buildTools = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$d8 = Join-Path $buildTools.FullName "d8.bat"

Write-Host "SDK: $sdk"
Write-Host "android.jar: $androidJar"
Write-Host "d8: $d8"

$classesOut = Join-Path $root "build\guard-classes"
Remove-Item $classesOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classesOut | Out-Null

$sources = @(
    (Join-Path $root "guard\com\apkharden\guard\GuardProvider.java"),
    (Join-Path $root "guard\com\apkharden\guard\AntiDebug.java"),
    (Join-Path $root "guard\com\apkharden\guard\AntiTamper.java")
)
& javac -encoding UTF-8 -source 8 -target 8 -cp $androidJar -d $classesOut $sources

$dexOut = Join-Path $root "build\guard-dex"
Remove-Item $dexOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dexOut | Out-Null
$classes = Get-ChildItem $classesOut -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --min-api 23 --output $dexOut $classes --lib $androidJar

New-Item -ItemType Directory -Force (Join-Path $root "src\main\resources") | Out-Null
Copy-Item (Join-Path $dexOut "classes.dex") (Join-Path $root "src\main\resources\guard.dex") -Force
Write-Host "guard.dex written to src/main/resources/guard.dex"
