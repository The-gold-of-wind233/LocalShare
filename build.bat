@echo off
setlocal
title LocalShare Android 编译

cd /d "%~dp0"

echo ============================================
echo   LocalShare Android 编译
echo ============================================
echo.

rem ---------- 检查 gradlew 是否存在 ----------
rem  本项目没有随包附带 gradle-wrapper.jar（二进制文件），
rem  因此无法直接命令行编译。必须先由 Android Studio 打开一次，
rem  它会自动生成 gradlew / gradlew.bat / wrapper jar。
if not exist "gradlew.bat" goto :nogradlew

rem ---------- 检查 Java ----------
if exist "%JAVA_HOME%\bin\java.exe" goto :javaok
echo [!] 未设置 JAVA_HOME，尝试用系统默认 java...

:javaok
java -version 2>&1
if errorlevel 1 goto :nojava

echo.
echo 正在编译 Debug APK...
echo.
call gradlew.bat assembleDebug
if errorlevel 1 goto :buildfail

goto :done

:nogradlew
echo [提示] 本项目尚未生成 Gradle 启动脚本。
echo.
echo   请用 Android Studio 打开本文件夹编译：
echo     1. 打开 Android Studio
echo     2. File - Open - 选本文件夹（含 settings.gradle.kts 的这层）
echo     3. 等待右下角 Gradle Sync 完成
echo     4. Build - Build Bundle(s)/APK(s) - Build APK(s)
echo     5. 完成后点右下角 locate 链接找到 apk
echo.
echo   不想装 Android Studio？见 CLOUD-BUILD.md（GitHub 免费云编译）
echo.
echo   Android Studio 首次打开时会自动生成 gradlew.bat，
echo   之后再双击本脚本就能命令行编译了。
echo.
echo   详细图文步骤见 ANDROID-BUILD.md
echo.
pause
exit /b 1

:nojava
echo.
echo [错误] 没有可用的 Java。
echo        Android Studio 自带 JDK，用它打开项目即可，不用单独装。
echo.
pause
exit /b 1

:buildfail
echo.
echo [错误] 编译失败。常见原因：
echo     1. 未安装 Android SDK（Android Studio 里 SDK Manager 装）
echo     2. 未同意 SDK licenses
echo     3. 网络不通，依赖下不下来
echo.
echo   详见 ANDROID-BUILD.md
echo.
pause
exit /b 1

:done
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
