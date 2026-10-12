@echo off
rem Starts the Aria proxy. The first run sets up a private Python environment in .venv.
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
    echo First run: installing yt-dlp and deno into .venv ^(takes a minute^)...
    py -3 -m venv .venv || python -m venv .venv || goto :nopython
    .venv\Scripts\python -m pip install --quiet --upgrade pip
)
rem Keep yt-dlp current. YouTube breaks old versions often.
.venv\Scripts\python -m pip install --quiet --upgrade -r requirements.txt
.venv\Scripts\python aria_proxy.py %*
pause
goto :eof
:nopython
echo Python 3 is not installed. Get it from https://www.python.org/downloads/ and tick "Add python.exe to PATH".
pause
