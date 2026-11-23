param([string] $OutputDirectory = (Join-Path $PSScriptRoot '..\desktop\build\native'))
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\native\media-bridge.cpp')).Path
$nativeOutput = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (!(Test-Path -LiteralPath $vswhere)) { throw 'Install Visual Studio Build Tools with Desktop development with C++.' }
$studio = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$studio) { throw 'The Visual C++ x64 build tools are required.' }
$env:SPICY_VC_VARS = Join-Path $studio.Trim() 'VC\Auxiliary\Build\vcvars64.bat'
$env:SPICY_NATIVE_SOURCE = $source
$env:SPICY_NATIVE_OUTPUT = $nativeOutput
& cmd.exe /d /c (Join-Path $PSScriptRoot 'compile-native.cmd')
if ($LASTEXITCODE -ne 0) { throw "Media bridge build failed ($LASTEXITCODE)." }
