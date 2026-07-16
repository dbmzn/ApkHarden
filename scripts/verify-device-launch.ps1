param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,
    [Parameter(Mandatory = $true)]
    [string]$PackageName,
    [string]$Serial = "",
    [ValidateRange(1, 120)]
    [int]$WaitSeconds = 20,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$apkPath = (Resolve-Path -LiteralPath $Apk).Path

if (-not $Serial) {
    $devices = @(
        & adb devices |
            Select-String '^\S+\s+device$' |
            ForEach-Object { ($_ -split '\s+')[0] }
    )
    if ($devices.Count -ne 1) {
        throw "Expected exactly one connected device, found $($devices.Count). Pass -Serial explicitly."
    }
    $Serial = $devices[0]
}

$deviceArgs = @('-s', $Serial)
$api = (& adb @deviceArgs shell getprop ro.build.version.sdk).Trim()
$pageSizeOutput = & adb @deviceArgs shell getconf PAGESIZE 2>$null
$pageSize = if ($pageSizeOutput) { ($pageSizeOutput -join '').Trim() } else { 'unknown' }
$abis = (& adb @deviceArgs shell getprop ro.product.cpu.abilist).Trim()

if (-not $SkipInstall) {
    $install = & adb @deviceArgs install -r $apkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "APK install failed:`n$($install -join [Environment]::NewLine)"
    }
}

& adb @deviceArgs logcat -c 2>$null | Out-Null
& adb @deviceArgs shell am force-stop $PackageName | Out-Null
$launch = & adb @deviceArgs shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Launcher invocation failed:`n$($launch -join [Environment]::NewLine)"
}

Start-Sleep -Seconds $WaitSeconds
$appPidOutput = & adb @deviceArgs shell pidof $PackageName 2>$null
$appPid = if ($appPidOutput) { ($appPidOutput -join '').Trim() } else { '' }
$activities = (& adb @deviceArgs shell dumpsys activity activities 2>$null) -join [Environment]::NewLine
$packagePattern = [Regex]::Escape($PackageName)
$crashLog = (
    & adb @deviceArgs logcat -d -v threadtime `
        'AndroidRuntime:E' 'ActivityManager:E' 'libc:F' 'DEBUG:F' 'linker:E' 'ApkHarden:E' '*:S' 2>&1
) -join [Environment]::NewLine

if (-not $appPid) {
    throw "App process is not alive after $WaitSeconds seconds.`n$crashLog"
}
if ($activities -notmatch "(?m)(mResumedActivity|topResumedActivity|ResumedActivity).*$packagePattern") {
    throw "No resumed activity found for $PackageName after $WaitSeconds seconds.`n$crashLog"
}
if ($crashLog -match 'FATAL EXCEPTION|Fatal signal|UnsatisfiedLinkError|APH-E\d{3}') {
    throw "Crash or ApkHarden initialization error detected.`n$crashLog"
}

Write-Host "PASS package=$PackageName serial=$Serial api=$api pageSize=$pageSize abis=$abis pid=$appPid"
