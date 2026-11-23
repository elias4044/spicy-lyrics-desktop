@echo off
setlocal
call "%SPICY_VC_VARS%" >nul
if errorlevel 1 exit /b %errorlevel%
pushd "%SPICY_NATIVE_OUTPUT%"
cl /nologo /std:c++20 /EHsc /O2 /MT /utf-8 /I"%WindowsSdkDir%Include\%WindowsSDKVersion%cppwinrt" "%SPICY_NATIVE_SOURCE%" /Fe:media-bridge.exe /link /subsystem:windows /entry:mainCRTStartup windowsapp.lib winmm.lib
set "BUILD_EXIT=%errorlevel%"
popd
exit /b %BUILD_EXIT%
