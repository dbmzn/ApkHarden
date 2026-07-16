# Builds the encrypted-DEX shell DEX and 16KB-compatible native libraries.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $localProperties = Join-Path $root "local.properties"
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties | Where-Object { $_ -match '^\s*sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) { throw "Android SDK not found" }

$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName "android.jar"
$buildTools = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$d8 = Join-Path $buildTools.FullName "d8.bat"
$ndk = Get-ChildItem "$sdk\ndk" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$cmakeRoot = Get-ChildItem "$sdk\cmake" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$cmake = Join-Path $cmakeRoot.FullName "bin\cmake.exe"
$ninja = Join-Path $cmakeRoot.FullName "bin\ninja.exe"

if (-not (Test-Path $androidJar)) { throw "android.jar not found" }
if (-not (Test-Path $d8)) { throw "d8 not found" }
if (-not $ndk -or -not (Test-Path $cmake)) { throw "Android NDK/CMake not found" }

$classesOut = Join-Path $root "build\shell-classes"
$dexOut = Join-Path $root "build\shell-dex"
Remove-Item $classesOut -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $dexOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classesOut, $dexOut | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $root "guard") -Recurse -Filter *.java | ForEach-Object FullName
$sources += Get-ChildItem (Join-Path $root "shell") -Recurse -Filter *.java | ForEach-Object FullName
& javac -encoding UTF-8 -source 8 -target 8 -cp $androidJar -d $classesOut $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$classes = Get-ChildItem $classesOut -Recurse -Filter *.class | ForEach-Object FullName
& $d8 --min-api 23 --output $dexOut $classes --lib $androidJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$resourceRoot = Join-Path $root "src\main\resources"
New-Item -ItemType Directory -Force $resourceRoot | Out-Null
Copy-Item (Join-Path $dexOut "classes.dex") (Join-Path $resourceRoot "shell.dex") -Force

$abis = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
foreach ($abi in $abis) {
    $nativeBuild = Join-Path $root "build\native-shell\$abi"
    Remove-Item $nativeBuild -Recurse -Force -ErrorAction SilentlyContinue
    & $cmake -S (Join-Path $root "native") -B $nativeBuild -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$($ndk.FullName)\build\cmake\android.toolchain.cmake" `
        "-DANDROID_ABI=$abi" `
        "-DANDROID_PLATFORM=android-23" `
        "-DCMAKE_BUILD_TYPE=Release"
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed for $abi" }
    & $cmake --build $nativeBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "Native build failed for $abi" }
    $target = Join-Path $resourceRoot "shell-libs\$abi"
    New-Item -ItemType Directory -Force $target | Out-Null
    Copy-Item (Join-Path $nativeBuild "libapkharden.so") (Join-Path $target "libapkharden.so") -Force
}

Write-Host "shell.dex and native shell libraries written to src/main/resources"
