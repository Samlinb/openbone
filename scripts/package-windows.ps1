[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [string]$RuntimeHome,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    $JdkHome = Split-Path -Parent (Split-Path -Parent (Get-Command jpackage.exe -ErrorAction Stop).Source)
}
$jpackage = Join-Path $JdkHome 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw 'Packaging requires a Windows JDK 17+ with jpackage.' }
$csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw 'The Windows .NET Framework C# compiler is required to build the offline installer.' }
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml')
$version = [string]$pom.project.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Windows release versions must have the form major.minor.patch.' }
$jdkRelease = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($jdkRelease -notmatch 'OS_ARCH="(amd64|x86_64)"') { throw 'Use an x64 JDK for the Windows x64 package.' }
Push-Location $root
try {
    if (-not $SkipBuild) {
        & $Maven -B verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    }
    $jar = Join-Path $root "target/openbone-$version.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar. Build the shaded JAR first." }
    # A fresh input directory prevents test output, older JARs and local files entering the release.
    $work = Join-Path $root ('target/windows-package/' + [guid]::NewGuid().ToString('N'))
    $inputDir = New-Item -ItemType Directory -Path (Join-Path $work 'input')
    Copy-Item -LiteralPath $jar -Destination $inputDir.FullName
    $cliProperties = Join-Path $work 'cli.properties'
    Set-Content -LiteralPath $cliProperties -Encoding ASCII -Value 'win-console=true'
    $packageArgs = @('--type', 'app-image', '--name', 'Openbone', '--app-version', $version,
        '--vendor', 'Openbone', '--description', '2D skeletal animation editor',
        '--input', $inputDir.FullName, '--main-jar', "openbone-$version.jar",
        '--main-class', 'io.github.samlinb.openbone.EditorApplication',
        '--java-options', '-Dfile.encoding=UTF-8',
        '--dest', (Join-Path $work 'image'), '--add-launcher', "Openbone-cli=$cliProperties")
    if ($RuntimeHome) {
        $RuntimeHome = (Resolve-Path -LiteralPath $RuntimeHome).Path
        $runtimeRelease = Get-Content -LiteralPath (Join-Path $RuntimeHome 'release') -Raw
        $runtimeVersion = [regex]::Match($runtimeRelease, 'JAVA_VERSION="(\d+)\.').Groups[1].Value
        if ($runtimeRelease -notmatch 'OS_ARCH="(amd64|x86_64)"' -or
            $runtimeRelease -notmatch 'OS_NAME="Windows"' -or
            -not $runtimeVersion -or [int]$runtimeVersion -lt 17 -or
            -not (Test-Path -LiteralPath (Join-Path $RuntimeHome 'bin/server/jvm.dll'))) {
            throw 'RuntimeHome must be a Java 17+ Windows x64 runtime.'
        }
        $packageArgs += @('--runtime-image', $RuntimeHome)
    } else {
        # Include modules used by Swing, reflection, environment diagnostics and the optional MCP server.
        $packageArgs += @('--add-modules', 'java.desktop,java.logging,java.management,java.net.http,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets')
    }
    & $jpackage @packageArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    $image = Join-Path $work 'image/Openbone'
    Copy-Item -LiteralPath (Join-Path $root 'packaging/WINDOWS-README.txt') -Destination (Join-Path $image 'README.txt')
    Copy-Item -LiteralPath (Join-Path $root 'src/main/resources/THIRD_PARTY.txt') -Destination $image
    # Keep the runtime legal notices generated/copied by jpackage, and expose application notices too.
    Copy-Item -LiteralPath (Join-Path $root 'src/main/resources/licenses') -Destination (Join-Path $image 'licenses') -Recurse
    $manifest = @(Get-ChildItem -LiteralPath $image -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($image.Length + 1).Replace('\', '/')
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative
    })
    [IO.File]::WriteAllLines((Join-Path $image 'payload.sha256'), $manifest, [Text.UTF8Encoding]::new($false))
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $payload = Join-Path $work 'payload.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($image, $payload)
    $dist = New-Item -ItemType Directory -Path (Join-Path $root 'target/dist') -Force
    $baseName = "Openbone-$version-windows-x64"
    $setup = Join-Path $dist.FullName "$baseName-setup.exe"
    & $csc /nologo /target:winexe /platform:x64 "/out:$setup" "/resource:$payload,payload.zip" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll (Join-Path $root 'packaging/installer/Setup.cs')
    if ($LASTEXITCODE -ne 0) { throw 'Offline installer compilation failed.' }
    $zip = Join-Path $dist.FullName "$baseName-portable.zip"
    # A top-level folder avoids spilling files into the user's Downloads directory.
    $temporaryZip = Join-Path $work "$baseName-portable.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $work 'image'), $temporaryZip)
    Copy-Item -LiteralPath $temporaryZip -Destination $zip -Force
    $checksums = @($zip, $setup | ForEach-Object {
        (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + (Split-Path -Leaf $_)
    })
    [IO.File]::WriteAllLines((Join-Path $dist.FullName "$baseName.sha256"), $checksums, [Text.UTF8Encoding]::new($false))
    Write-Host "Created $zip"
    Write-Host "Created $setup"
} finally { Pop-Location }
