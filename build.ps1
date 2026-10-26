# DSH Mobile 构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner（不依赖 Gradle）
$ErrorActionPreference = "Stop"

$Root     = "D:\mine\dsh-mobile"
$Sdk      = "$Root\toolchain\sdk"
$BT       = "$Sdk\build-tools\36.0.0"
$AndroidJar = "$Sdk\platforms\android-34\android.jar"
$JavaHome = "D:\pycharm\PyCharm 2026.1.4\jbr"
$Src      = "$Root\app\src\main"
$Out      = "$Root\build"
$Dist     = "$Root\dist"

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$BT;$env:PATH"

$VersionCode = 5
$VersionName = "1.4.0"

Write-Host "== 0/7 准备目录" -ForegroundColor Cyan
foreach ($d in "$Out\gen", "$Out\classes", "$Out\dex", $Dist) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

if (-not (Test-Path "$Src\res\mipmap-xxxhdpi\ic_launcher.png")) {
    Write-Host "== 生成图标" -ForegroundColor Cyan
    & "$Root\toolchain\icons.ps1"
}

Write-Host "== 1/7 编译资源" -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir "$Src\res" -o "$Out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "== 2/7 链接资源与清单" -ForegroundColor Cyan
& "$BT\aapt2.exe" link `
    -o "$Out\base.apk" `
    -I $AndroidJar `
    --manifest "$Src\AndroidManifest.xml" `
    -R "$Out\res.zip" `
    --java "$Out\gen" `
    --min-sdk-version 24 `
    --target-sdk-version 34 `
    --version-code $VersionCode `
    --version-name $VersionName `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "== 3/7 编译 Java" -ForegroundColor Cyan
if (Test-Path "$Out\classes") { Remove-Item -Recurse -Force "$Out\classes" }
New-Item -ItemType Directory -Force -Path "$Out\classes" | Out-Null

$sources = @()
$sources += (Get-ChildItem "$Src\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem "$Out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host ("    java 文件数: " + $sources.Count)

$javacArgs = @("-encoding", "UTF-8", "--release", "8", "-nowarn", "-Xlint:none",
    "-classpath", $AndroidJar, "-d", "$Out\classes") + $sources
# javac 会把「使用了已过时 API」之类的提示写到 stderr，不能因此中断，只看退出码
$strict = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacLog = & "$JavaHome\bin\javac.exe" @javacArgs 2>&1
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $strict
if ($javacExit -ne 0) {
    $javacLog | ForEach-Object { Write-Host $_ }
    throw "javac 失败"
}

Write-Host "== 4/7 dex 转换" -ForegroundColor Cyan
if (Test-Path "$Out\dex") { Remove-Item -Recurse -Force "$Out\dex" }
New-Item -ItemType Directory -Force -Path "$Out\dex" | Out-Null
$classes = Get-ChildItem "$Out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Write-Host ("    class 数量: " + $classes.Count)

# class 多了以后逐个传参会超过 Windows 命令行上限，先打成 jar 再交给 d8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$classesJar = "$Out\classes.jar"
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
$jar = [System.IO.Compression.ZipFile]::Open($classesJar, 'Create')
try {
    foreach ($c in $classes) {
        $rel = $c.Substring("$Out\classes".Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($jar, $c, $rel, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally {
    $jar.Dispose()
}
$d8args = @("--release", "--min-api", "24", "--lib", $AndroidJar, "--output", "$Out\dex", $classesJar)
& "$BT\d8.bat" @d8args
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "== 5/7 打包 dex" -ForegroundColor Cyan
Copy-Item "$Out\base.apk" "$Out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$Out\unsigned.apk", 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$stream = $entry.Open()
$dexBytes = [System.IO.File]::ReadAllBytes("$Out\dex\classes.dex")
$stream.Write($dexBytes, 0, $dexBytes.Length)
$stream.Close()
$zip.Dispose()

Write-Host "== 6/7 对齐" -ForegroundColor Cyan
& "$BT\zipalign.exe" -f -p 4 "$Out\unsigned.apk" "$Out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 7/7 签名" -ForegroundColor Cyan
$ks = "$Root\toolchain\debug.keystore"
$strict = $ErrorActionPreference
$ErrorActionPreference = "Continue"
if (-not (Test-Path $ks)) {
    & "$JavaHome\bin\keytool.exe" -genkeypair `
        -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}
$ErrorActionPreference = $strict
if (-not (Test-Path $ks)) { throw "keystore 生成失败" }

$apk = "$Dist\dsh-mobile.apk"
if (Test-Path $apk) { Remove-Item $apk -Force }
$ErrorActionPreference = "Continue"
& "$BT\apksigner.bat" sign `
    --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey `
    --min-sdk-version 24 `
    --out $apk "$Out\aligned.apk" 2>&1 | Out-Null
$signExit = $LASTEXITCODE
& "$BT\apksigner.bat" verify --min-sdk-version 24 $apk 2>&1 | Out-Null
$verifyExit = $LASTEXITCODE
$ErrorActionPreference = $strict
if ($signExit -ne 0) { throw "apksigner 签名失败 ($signExit)" }
if ($verifyExit -ne 0) { throw "签名校验失败 ($verifyExit)" }

$size = [Math]::Round((Get-Item $apk).Length / 1MB, 2)
Write-Host ""
Write-Host "构建成功: $apk  ($size MB)" -ForegroundColor Green
Write-Host "BUILD_OK"
