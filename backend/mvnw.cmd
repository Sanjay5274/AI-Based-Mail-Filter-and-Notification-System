@REM ----------------------------------------------------------------------------
@REM Maven Wrapper Batch Script for InboxIQ Backend
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12"

set "MAVEN_CMD=C:\Users\sanja\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd"

if exist "%MAVEN_CMD%" (
    call "%MAVEN_CMD%" %*
) else (
    where mvn >nul 2>&1
    if %ERRORLEVEL% equ 0 (
        call mvn %*
    ) else (
        echo [ERROR] Maven not found. Please install Maven or configure PATH.
        exit /b 1
    )
)

