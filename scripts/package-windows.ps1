[CmdletBinding()]
param(
    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\desktop\build\package'),
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $Version = '0.1.0',
    [string] $WixBin,
    [switch] $PortableOnly,
    [switch] $SkipTests,
    [string] $AppImage,
    [string] $WorkDirectory
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = (New-Item -Path $OutputDirectory -ItemType Directory -Force).FullName
$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jpackage.exe' } else { 'jpackage.exe' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$icon = $null
$diagnostics = @(if ($VerbosePreference -eq 'Continue') { '--verbose' })
$repository = 'https://github.com/elias4044/spicy-lyrics-desktop'
$versionParts = $Version.Split('.') | ForEach-Object { [int] $_ }
if ($versionParts[0] -gt 255 -or $versionParts[1] -gt 255 -or $versionParts[2] -gt 65535) {
    throw 'Use a Windows Installer version in the range 0.0.0 to 255.255.65535.'
}

Push-Location $project
try {
    Write-Host "Preparing Spicy Lyrics Desktop $Version"
    if (!$PortableOnly) {
        if ($WixBin) { $env:Path = "$( (Resolve-Path -LiteralPath $WixBin).Path );$env:Path" }
        if (!(Get-Command candle.exe -ErrorAction SilentlyContinue) -or !(Get-Command light.exe -ErrorAction SilentlyContinue)) {
            throw 'WiX Toolset 3.14.1 is required. Add its bin directory to PATH or pass -WixBin.'
        }
    }
    if (!$AppImage) {
        if (Test-Path -LiteralPath (Join-Path $output 'SpicyLyrics')) {
            throw "A portable build already exists in $output. Choose a fresh -OutputDirectory."
        }
        Write-Host 'Building the Windows media bridge'
        & (Join-Path $PSScriptRoot 'build-native.ps1')
        $gradleTasks = @(':desktop:installDist')
        if (!$SkipTests) { $gradleTasks = @(':core:test') + $gradleTasks }
        if ($SkipTests) { Write-Host 'Building the desktop app (tests skipped)' }
        else { Write-Host 'Running core tests and building the desktop app' }
        & .\gradlew.bat @gradleTasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
        $appInput = Join-Path $project 'desktop\build\install\SpicyLyrics\lib'
        Copy-Item -LiteralPath (Join-Path $project 'desktop\build\native\media-bridge.exe') -Destination $appInput
        Write-Host 'Generating the gray launcher icon from the desktop app'
        $icon = Join-Path ([IO.Path]::GetTempPath()) ("SpicyLyrics-" + [guid]::NewGuid() + ".ico")
        & $java '-Djava.awt.headless=true' -cp (Join-Path $appInput '*') spicy.desktop.LauncherIcon $icon
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $icon -PathType Leaf)) {
            throw 'Windows launcher icon generation failed.'
        }
        Write-Host 'Creating the portable app and embedding its Windows icon'
        $imageArgs = @('--type', 'app-image', '--name', 'SpicyLyrics', '--app-version', $Version, '--icon', $icon,
            '--dest', $output, '--input', $appInput, '--main-class', 'spicy.desktop.MainKt', '--main-jar', 'desktop.jar',
            '--add-modules', 'java.desktop,java.logging,java.net.http,java.naming,jdk.httpserver,jdk.crypto.ec,java.management',
            '--vendor', 'elias4044', '--copyright', 'Spicy Lyrics Desktop by elias4044. GNU AGPL v3.')
        & $jpackage @imageArgs @diagnostics
        if ($LASTEXITCODE -ne 0) { throw 'Windows application image build failed.' }
        $AppImage = Join-Path $output 'SpicyLyrics'
        Copy-Item -LiteralPath '.\LICENSE', '.\THIRD_PARTY_NOTICES.md' -Destination $AppImage
        Copy-Item -LiteralPath '.\browser-companion' -Destination $AppImage -Recurse
    }
    $AppImage = (Resolve-Path -LiteralPath $AppImage).Path
    if (!(Test-Path -LiteralPath (Join-Path $AppImage 'SpicyLyrics.exe') -PathType Leaf)) {
        throw 'AppImage must be a SpicyLyrics jpackage application image.'
    }
    if ($PortableOnly) {
        Write-Host "Portable app ready: $AppImage"
        Write-Output $AppImage
        return
    }

    $installerName = 'SpicyLyrics-' + $Version + '.msi'
    $installer = Join-Path $output $installerName
    if (Test-Path -LiteralPath $installer) { throw "An installer already exists at $installer." }
    Write-Host 'Building the MSI with the Spicy Lyrics Desktop Start menu shortcut'
    $msiArgs = @('--type', 'msi', '--app-image', $AppImage, '--name', 'SpicyLyrics',
        '--app-version', $Version, '--dest', $output, '--vendor', 'elias4044', '--description', 'Synced lyrics for Windows',
        '--win-per-user-install', '--win-menu',
        '--win-menu-group', 'Spicy Lyrics Desktop', '--win-dir-chooser',
        '--win-help-url', "$repository/issues", '--win-update-url', "$repository/releases",
        '--win-upgrade-uuid', 'f4745e6d-c437-4609-af2b-865051bb4d43')
    if ($WorkDirectory) { $msiArgs += @('--temp', $WorkDirectory) }
    & $jpackage @msiArgs @diagnostics
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $installer)) {
        throw "Windows installer build failed (jpackage exit code $LASTEXITCODE). Pass -Verbose for WiX details."
    }
    Write-Host 'Writing the installer checksum'
    $checksum = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -Encoding ascii -LiteralPath "$installer.sha256" -Value "$checksum  $installerName"
    Write-Host "Installer ready: $installer"
    Write-Output $installer
} finally {
    if ($icon) { Remove-Item -LiteralPath $icon -ErrorAction SilentlyContinue }
    Pop-Location
}
