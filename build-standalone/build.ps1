$ErrorActionPreference = "Stop"

$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$buildTools = "$sdkRoot\build-tools\33.0.2"
$platform = "$sdkRoot\platforms\android-33"
$javaHome = "C:\Users\Lenovo\AppData\Local\Programs\jdk-17\jdk17.0.20_12"

$aapt2 = "$buildTools\aapt2.exe"
$d8 = "$buildTools\d8.bat"
$zipalign = "$buildTools\zipalign.exe"
$apksigner = "$buildTools\apksigner.bat"
$androidJar = "$platform\android.jar"
$javac = "$javaHome\bin\javac.exe"
$keytool = "$javaHome\bin\keytool.exe"

$baseDir = "c:\Users\Lenovo\Desktop\AIntiRADAR\build-standalone"
$binDir = "$baseDir\bin"
$genDir = "$baseDir\gen"
$classesDir = "$baseDir\classes"

# Clean
Remove-Item -Path $binDir, $genDir, $classesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $binDir, $genDir, $classesDir | Out-Null

Write-Host "1. AAPT2 Compile Resources..."
$resZip = "$binDir\resources.zip"
& $aapt2 compile --dir "$baseDir\res" -o $resZip

Write-Host "2. AAPT2 Link Resources & Generate R.java..."
$unalignedApk = "$binDir\app.unaligned.apk"
& $aapt2 link -I $androidJar `
    --manifest "$baseDir\AndroidManifest.xml" `
    --java $genDir `
    -A "$baseDir\assets" `
    -o $unalignedApk `
    $resZip `
    --auto-add-overlay

Write-Host "3. Javac Compile Java Sources..."
$javaFiles = Get-ChildItem -Path "$baseDir\src", $genDir -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
$libJars = Get-ChildItem -Path "$baseDir\libs\extracted-jars" -Filter "*.jar" | Select-Object -ExpandProperty FullName
$libsClasspath = ($libJars -join ";")
& $javac -encoding UTF-8 -cp "$androidJar;$libsClasspath" -d $classesDir $javaFiles

Write-Host "4. D8 Convert Classes to DEX..."
$classFiles = Get-ChildItem -Path $classesDir -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName
& $javaHome\bin\java.exe -cp "$buildTools\lib\d8.jar" com.android.tools.r8.D8 --output "$binDir" --lib "$androidJar" --min-api 26 $classFiles $libJars

Write-Host "5. Packaging DEX into APK..."
$jarExe = "$javaHome\bin\jar.exe"
Push-Location $binDir
$dexFiles = Get-ChildItem -Filter "classes*.dex" | Select-Object -ExpandProperty Name
foreach ($df in $dexFiles) {
    & $jarExe -uf $unalignedApk $df
}
Pop-Location

Write-Host "6. Zipalign APK..."
$alignedApk = "$binDir\app.aligned.apk"
& $zipalign -f -p 4 $unalignedApk $alignedApk

Write-Host "7. Signing APK..."
$keystore = "$baseDir\debug.keystore"
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -v `
        -keystore $keystore `
        -storepass android `
        -alias androiddebugkey `
        -keypass android `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

$finalApk = "c:\Users\Lenovo\Desktop\AIntiRADAR\AIntiRADAR.apk"
& cmd.exe /c "set JAVA_HOME=$javaHome&& set PATH=$javaHome\bin;%PATH%&& `"$apksigner`" sign --ks `"$keystore`" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out `"$finalApk`" `"$alignedApk`""

Write-Host "8. Verifying APK Signature..."
& cmd.exe /c "set JAVA_HOME=$javaHome&& set PATH=$javaHome\bin;%PATH%&& `"$apksigner`" verify `"$finalApk`""

Write-Host "SUCCESS! Built APK at: $finalApk"
Get-Item $finalApk | Select-Object Name, Length
