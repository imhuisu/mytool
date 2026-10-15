# 일정 바탕화면 위젯 빌드·설치 (Windows PowerShell)
#   powershell -ExecutionPolicy Bypass -File desktop\install.ps1
# - 빌드 도구: %LOCALAPPDATA%\mytool-desktop\dotnet (.NET 8 SDK, 사용자 폴더 설치)
# - 설치 위치: %LOCALAPPDATA%\mytool-desktop\app  (시작 메뉴에 "일정 위젯" 바로가기, Windows 시작 시 자동 실행)
# - 결과물은 .NET Framework 4.8 프로그램이라 다른 윈도우 10/11 PC에도 폴더째 복사하면 실행됨 (zip: ..\app\sched-desktop.zip)
$ErrorActionPreference = 'Stop'
$base = "$env:LOCALAPPDATA\mytool-desktop"
$env:DOTNET_ROOT = "$base\dotnet"; $env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'; $env:DOTNET_NOLOGO = '1'; $env:DOTNET_MULTILEVEL_LOOKUP = '0'
$src = $PSScriptRoot
$repo = Split-Path $src

& "$base\dotnet\dotnet.exe" build "$src\SchedDesk.csproj" -c Release -v q -nologo
if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
$out = "$src\bin\Release\net48"

# 실행 중이면 끄고 복사
Get-Process SchedWidget -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
New-Item -ItemType Directory -Force "$base\app" | Out-Null
Copy-Item "$out\*" "$base\app" -Recurse -Force

# 배포용 zip (사이트 app/ 에서 받기)
$zip = "$repo\app\sched-desktop.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path "$out\SchedWidget.exe", "$out\SchedWidget.exe.config", "$out\*.dll", "$out\runtimes" -DestinationPath $zip

# 시작 메뉴 바로가기 + 자동 실행
$lnk = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\일정 위젯.lnk"
$sh = New-Object -ComObject WScript.Shell
$s = $sh.CreateShortcut($lnk); $s.TargetPath = "$base\app\SchedWidget.exe"; $s.WorkingDirectory = "$base\app"; $s.Save()
Set-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "mytool-sched-desk" -Value "`"$base\app\SchedWidget.exe`""

Start-Process "$base\app\SchedWidget.exe"
"설치 완료: $base\app\SchedWidget.exe (zip $([math]::Round((Get-Item $zip).Length/1MB,2)) MB)"
