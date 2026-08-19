@echo off
cd /d "%~dp0"

REM Ensure Docker Postgres is up with host port 1984 mapped.
set /a tries=0
:wait_docker
set /a tries+=1
docker compose up -d >nul 2>&1
docker start astro_algo_postgres >nul 2>&1
powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient('127.0.0.1',1984); $c.Close(); exit 0 } catch { exit 1 }"
if %ERRORLEVEL%==0 goto ensure_funnel
if %tries% GEQ 90 (
  echo Postgres not ready after ~3 minutes
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto wait_docker

:ensure_funnel
REM Keep Tailscale Funnel pointing at algo-relay (8443).
tailscale serve --bg --https=8443 http://127.0.0.1:3334 >nul 2>&1
tailscale funnel --bg --https=8443 http://127.0.0.1:3334 >nul 2>&1

:run
REM Restart loop so a crash does not leave الرائج empty.
:loop
algo-relay.exe
echo algo-relay exited with code %ERRORLEVEL% — restarting in 5s...
timeout /t 5 /nobreak >nul
goto loop
