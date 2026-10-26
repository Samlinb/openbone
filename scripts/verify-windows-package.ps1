[CmdletBinding()]
param([switch]$Desktop)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml')
$version = [string]$pom.project.version
$baseName = "Openbone-$version-windows-x64"
$dist = Join-Path $root 'target/dist'
foreach ($row in Get-Content -LiteralPath (Join-Path $dist "$baseName.sha256")) {
    $hash, $name = $row -split '  ', 2
    if ((Get-FileHash -LiteralPath (Join-Path $dist $name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw "Release checksum mismatch: $name"
    }
}
$work = Join-Path $root ('target/windows-smoke/中文 path ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $dist "$baseName-portable.zip"), $work)
$image = Join-Path $work 'Openbone'
$environmentNames = @('PATH', 'JAVA_HOME', 'JRE_HOME', 'CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'LOCALAPPDATA')
$saved = @{}
foreach ($name in $environmentNames) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Assert-Launcher([string]$Directory) {
    $cli = Join-Path $Directory 'Openbone-cli.exe'
    $actual = & $cli --version
    if ($LASTEXITCODE -ne 0 -or "$actual".Trim() -ne "Openbone $version") { throw "Version check failed: $actual" }
    $environment = (& $cli --environment) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Environment diagnostics failed.' }
    $runtimePath = [IO.Path]::GetFullPath(($environment | ConvertFrom-Json).javaHome).TrimEnd('\')
    if ($runtimePath -ne (Join-Path $Directory 'runtime')) { throw "Launcher used an external runtime: $runtimePath" }
    # Exercise actual application dependencies and command-line failure propagation.
    & $cli --validate-project (Join-Path $work 'missing.json') 2>$null | Out-Null
    if ($LASTEXITCODE -ne 2) { throw 'Validation failure exit code was not propagated.' }
}
try {
    foreach ($name in $environmentNames) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
    $env:PATH = "$env:WINDIR\System32;$env:WINDIR"
    $env:LOCALAPPDATA = (New-Item -ItemType Directory -Path (Join-Path $work 'user-state')).FullName
    Assert-Launcher $image
    $setup = Join-Path $dist "$baseName-setup.exe"
    $installed = Join-Path $work '安装 test'
    $process = Start-Process -FilePath $setup -ArgumentList @('--install', ('"' + $installed + '"')) -PassThru -Wait -WindowStyle Hidden
    if ($process.ExitCode -ne 0) { throw 'Offline installation failed.' }
    Assert-Launcher $installed
    # Reinstall must work, and uninstall must retain an untracked user project.
    $sentinel = Join-Path $installed 'user-project.json'
    [IO.File]::WriteAllText($sentinel, 'retain this user file')
    $process = Start-Process -FilePath $setup -ArgumentList @('--install', ('"' + $installed + '"')) -PassThru -Wait -WindowStyle Hidden
    if ($process.ExitCode -ne 0) { throw 'Reinstallation failed.' }
    if ($Desktop) {
      foreach ($launcher in @('Openbone.exe', 'Openbone-cli.exe')) {
        $stdout = Join-Path $work "$launcher.stdout.log"
        $stderr = Join-Path $work "$launcher.stderr.log"
        $process = Start-Process -FilePath (Join-Path $installed $launcher) -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $editorProcess = $null
        try {
            $deadline = [DateTime]::UtcNow.AddSeconds(30)
            do {
                Start-Sleep -Milliseconds 250
                $process.Refresh()
                if ($process.HasExited) { throw 'Desktop launcher exited before showing the editor.' }
                # Windows jpackage may re-exec as a child; the supervising launcher has no window.
                $candidates = @($process) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" | ForEach-Object {
                    Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
                })
                $editorProcess = $candidates | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
            } while (-not $editorProcess -and [DateTime]::UtcNow -lt $deadline)
            if (-not $editorProcess) { throw 'No editor window appeared.' }
            Start-Sleep -Seconds 2
            if ($launcher -eq 'Openbone-cli.exe') {
                if ((Get-Content -LiteralPath $stdout -Raw) -notmatch 'GL firstRender') {
                    throw "The packaged runtime did not render the OpenGL viewport. See $stdout and $stderr"
                }
            }
            if (-not $editorProcess.CloseMainWindow() -or -not $process.WaitForExit(15000)) { throw 'Editor did not close normally.' }
        } finally {
            foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)")) {
                Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
            }
            if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        }
      }
    }
    $process = Start-Process -FilePath $setup -ArgumentList @('--uninstall', ('"' + $installed + '"')) -PassThru -Wait -WindowStyle Hidden
    if ($process.ExitCode -ne 0) { throw 'Uninstallation failed.' }
    if (-not (Test-Path -LiteralPath $sentinel) -or (Test-Path -LiteralPath (Join-Path $installed 'Openbone.exe'))) {
        throw 'Uninstall did not preserve user files and remove product files.'
    }
    Write-Host 'PASS: checksums, bundled runtime, native CLI, offline install/reinstall/uninstall, Unicode/space paths, user-file preservation.'
    if ($Desktop) { Write-Host 'PASS: both native launchers opened and closed the editor without system Java; packaged runtime rendered the OpenGL viewport.' }
} finally {
    foreach ($name in $environmentNames) {
        if ($null -eq $saved[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }
}
