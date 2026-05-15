@echo off
echo ============================================
echo   CloudQueryX - Setup Script
echo ============================================
echo.

:: Check Java version
java -version 2>nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Java not found. Please install JDK 17+
    echo Download from: https://adoptium.net/temurin/releases/?version=17
    exit /b 1
)

:: Check if Gradle wrapper exists
if not exist "gradlew.bat" (
    echo Gradle wrapper not found. Downloading...

    if not exist "gradle\wrapper" mkdir gradle\wrapper

    :: Download gradle-wrapper.jar
    powershell -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.6.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"

    if not exist "gradle\wrapper\gradle-wrapper.jar" (
        echo ERROR: Failed to download Gradle wrapper.
        echo Please install Gradle manually: https://gradle.org/install/
        exit /b 1
    )
)

echo.
echo Building CloudQueryX...
echo.
call gradlew.bat build -x test

if %ERRORLEVEL% neq 0 (
    echo.
    echo Build failed. Check the output above for errors.
    exit /b 1
)

echo.
echo ============================================
echo   Build successful!
echo ============================================
echo.
echo Run the web server (http://localhost:8080):
echo   gradlew runWeb
echo.
echo Run the interactive SQL shell:
echo   gradlew run --console=plain
echo.
echo Run tests (113 tests):
echo   gradlew test
echo.
echo Run benchmarks:
echo   gradlew run --args="benchmark"
echo.
