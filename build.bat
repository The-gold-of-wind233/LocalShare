@echo off
setlocal
title LocalShare Android 编译

cd /d "%~dp0"

echo ============================================
echo   LocalShare Android 编译
echo ============================================
echo.

if exist "%JAVA_HOME%\bin\java.exe" (
    echo [OK] JAVA_HOME = %JAVA_HOME%
) else (
    echo [!] 未设置 JAVA_HOME，尝试用系统默认 java...
)
java -version 2>&1
if errorlevel 1 (
    echo.
    echo [错误] 没有可用的 Java。请安装 JDK 17 并设置 JAVA_HOME。
    pause
    exit /b 1
)

echo.
echo 正在编译 Debug APK（首次需要联网下载 Gradle，可能较慢）...
echo.

call gradlew.bat assembleDebug

if errorlevel 1 (
    echo.
    echo [错误] 编译失败，请检查上面的报错。
    echo       常见原因：未安装 Android SDK / 未同意 licenses / 网络不通。
    pause
    exit /b 1
)

echo.
echo ============================================
echo   编译完成：
echo   app\build\outputs\apk\debug\app-debug.apk
echo.
echo   把这个 apk 传到手机安装即可。
echo ============================================
echo.
pause
endlocal
