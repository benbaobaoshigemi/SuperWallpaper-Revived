param(
    [string]$OutApk = ""
)
$ErrorActionPreference = "Stop"
$base = "G:\超级壁纸\SuperWallpaperNoAOD"
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\36.1.0"
$plat = "$sdk\platforms\android-36\android.jar"
$javac = "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot\bin\javac.exe"
$jar   = "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot\bin\jar.exe"
$keytool = "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot\bin\keytool.exe"
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
& "$bt\aapt2.exe" compile --dir "$base\res" -o $resZip 2>&1
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
$unsigned = "$base\build\unsigned.apk"
& "$bt\aapt2.exe" link -o $unsigned -I "$plat" --manifest "$base\AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 36 $resZip 2>&1
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

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
    & $keytool -genkeypair -keystore $ks -storepass android -alias androiddebugkey -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 2>&1 | Out-Null
}
$final = if ($OutApk) { $OutApk } else { "$base\SuperWallpaperNoAOD.apk" }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $final $aligned 2>&1
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Output "== DONE: $final =="
Get-Item $final | Select-Object FullName, Length
