# Build script for the Xposed module.
# Requirements: JDK 17, Android SDK (build-tools + platform android-36), PowerShell.
# Tool discovery order: ANDROID_HOME / ANDROID_SDK_ROOT -> %LOCALAPPDATA%\Android\Sdk
#                       JAVA_HOME -> common Adoptium install path
param(
    [string]$OutApk = ""
)
$ErrorActionPreference = "Stop"
$base = $PSScriptRoot

# ---- SDK / platform / build-tools discovery ----
if ($env:ANDROID_HOME) {
    $sdk = $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $sdk = $env:ANDROID_SDK_ROOT
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk") {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk"
} else {
    throw "Android SDK not found. Set ANDROID_HOME / ANDROID_SDK_ROOT first."
}
$bt = Get-ChildItem "$sdk\build-tools" -Directory -ErrorAction Stop |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $bt) { throw "No build-tools found under $sdk\build-tools" }
$bt = $bt.FullName
$plat = Get-ChildItem "$sdk\platforms" -Directory -Filter "android-*" -ErrorAction Stop |
    Sort-Object { [int]$_.Name.Replace('android-', '') } -Descending | Select-Object -First 1
if (-not $plat) { throw "No platform found under $sdk\platforms" }
$plat = Join-Path $plat.FullName "android.jar"

# ---- JDK discovery ----
$jdkRoot = $env:JAVA_HOME
if (-not $jdkRoot -or -not (Test-Path "$jdkRoot\bin\javac.exe")) {
    $candidates = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft"
    )
    foreach ($c in $candidates) {
        $found = Get-ChildItem $c -Directory -Filter "*17*" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) { $jdkRoot = $found.FullName; break }
    }
}
if (-not $jdkRoot -or -not (Test-Path "$jdkRoot\bin\javac.exe")) {
    throw "JDK not found. Set JAVA_HOME first."
}
$javac   = "$jdkRoot\bin\javac.exe"
$jar     = "$jdkRoot\bin\jar.exe"
$keytool = "$jdkRoot\bin\keytool.exe"
$api = "$base\libs\xposed-api-82.jar"

$cls = "$base\build\classes"
$dex = "$base\build\dex"
$stage = "$base\build\stage"
Remove-Item -Recurse -Force $cls, $dex, $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $cls, $dex, $stage | Out-Null

Write-Output "== javac =="
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$plat" -classpath "$api" -d "$cls" (Get-ChildItem "$base\src" -Recurse -Filter *.java | ForEach-Object FullName) 2>&1 | Where-Object { $_ -notmatch 'warning:|Note:' }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Output "== d8 =="
& "$bt\d8.bat" --release --lib "$plat" --lib "$api" --min-api 26 --output "$dex" (Get-ChildItem "$cls" -Recurse -Filter *.class | ForEach-Object FullName) 2>&1
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Output "== aapt2 =="
$resZip = "$base\build\res.zip"
$unsigned = "$base\build\unsigned.apk"
# aapt2 cannot open directories whose absolute path contains non-ASCII characters
# (Windows encoding bug), so run it with a relative path from the module dir.
Push-Location $base
try {
    & "$bt\aapt2.exe" compile --dir "res" -o "build\res.zip" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
    & "$bt\aapt2.exe" link -o "build\unsigned.apk" -I "$plat" --manifest "AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 36 "build\res.zip" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
} finally {
    Pop-Location
}

Write-Output "== add dex + assets =="
Copy-Item "$dex\classes.dex" "$stage\classes.dex"
New-Item -ItemType Directory -Force -Path "$stage\assets" | Out-Null
Copy-Item "$base\assets\xposed_init" "$stage\assets\xposed_init"
& $jar uf "$unsigned" -C "$stage" classes.dex -C "$stage" assets/xposed_init 2>&1
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Output "== zipalign =="
$aligned = "$base\build\aligned.apk"
& "$bt\zipalign.exe" -f 4 $unsigned $aligned 2>&1
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Output "== sign =="
$ks = "$base\build\debug.keystore"
if (-not (Test-Path $ks)) {
    # keytool prints progress to stderr, which trips $ErrorActionPreference=Stop;
    # temporarily downgrade and check the exit code explicitly.
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $keytool -genkeypair -keystore $ks -storepass android -alias androiddebugkey -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 2>&1 | Out-Null
    $ErrorActionPreference = $oldEap
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$final = if ($OutApk) { $OutApk } else { "$base\SuperWallpaperNoAOD.apk" }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $final $aligned 2>&1
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Output "== DONE: $final =="
Get-Item $final | Select-Object FullName, Length
