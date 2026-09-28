@echo off
setlocal
title PingShield - push to GitHub
cd /d "%~dp0.."

echo.
echo === PingShield: pushing to GitHub ===
echo.

where git >nul 2>&1
if errorlevel 1 (
    echo [!] Git is not installed or not in PATH.
    echo     Download and install: https://git-scm.com/download/win
    echo     Then close this window and run this file again.
    echo.
    pause
    exit /b 1
)

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo [!] This folder is not a git repository - the hidden .git folder is missing.
    echo     Unpack the archive again: the whole folder, including hidden files.
    echo.
    pause
    exit /b 1
)

set LOGIN=
set /p LOGIN=Your GitHub login (the XXXX part of github.com/XXXX): 
if "%LOGIN%"=="" (
    echo [!] Login cannot be empty.
    pause
    exit /b 1
)

set REPO=
set /p REPO=Repository name (press Enter for "pingshield"): 
if "%REPO%"=="" set REPO=pingshield

git remote remove origin >nul 2>&1
git remote add origin https://github.com/%LOGIN%/%REPO%.git

echo.
echo Sending the main branch and tags to https://github.com/%LOGIN%/%REPO%.git
echo A browser window may open to sign in to GitHub - sign in there, not with your password.
echo.

git push -u origin main
if errorlevel 1 goto fail

git push --tags
if errorlevel 1 goto fail

echo.
echo === DONE ===
echo Repository: https://github.com/%LOGIN%/%REPO%
echo Actions:    https://github.com/%LOGIN%/%REPO%/actions
echo Release:    https://github.com/%LOGIN%/%REPO%/releases
echo.
echo Later changes:  git add -A ^&^& git commit -m "change" ^&^& git push
echo.
pause
exit /b 0

:fail
echo.
echo [!] Push failed. Check:
echo     - the login is exactly your GitHub username;
echo     - the repository exists at https://github.com/%LOGIN%/%REPO%
echo     - the repository is EMPTY (no README, no .gitignore, no license);
echo     - the browser sign-in to GitHub was completed.
echo.
pause
exit /b 1
