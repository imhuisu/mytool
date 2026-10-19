# 일정 위젯 APK 빌드 (Windows PowerShell)
#   powershell -ExecutionPolicy Bypass -File android\build.ps1
# - 안드로이드 빌드는 경로에 한글이 있으면 실패해서, 영문 경로로 복사해 빌드한다
# - 빌드 도구: %LOCALAPPDATA%\mytool-android (jdk, sdk, gradle)
# - 서명 키: %USERPROFILE%\.mytool\sched-release.jks, 비밀번호는 %USERPROFILE%\.gradle\gradle.properties
#   이 키가 없으면 폰에서 덮어 설치(업데이트)가 안 된다. 다른 PC에서 빌드하려면 키 파일과 비밀번호를 옮길 것
# - 테스트용: 결과는 %LOCALAPPDATA%\mytool-android\out 에만 (배포는 GitHub Actions)
$ErrorActionPreference = 'Stop'
$tools = "$env:LOCALAPPDATA\mytool-android"
$src = $PSScriptRoot
$repo = Split-Path $src
$work = "$tools\build-src"

$env:JAVA_HOME = "$tools\jdk"
$env:ANDROID_HOME = "$tools\sdk"

robocopy $src $work /MIR /XD .gradle build .kotlin /XF local.properties /NFL /NDL /NJH /NJS /NP | Out-Null
[IO.File]::WriteAllText("$work\local.properties", "sdk.dir=$(($tools -replace '\\','/'))/sdk`n")

Push-Location $work
try {
    & "$tools\gradle\bin\gradle.bat" assembleRelease bundleRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
} finally { Pop-Location }

# 결과는 저장소 밖(%LOCALAPPDATA%\mytool-android\out)에만. 배포는 GitHub Actions(.github/workflows/sched-widget.yml)가
# 플레이스토어 업로드와 app/sched-widget.apk·version.json 커밋까지 함 → 여기서 저장소 app/ 을 건드리면 버전이 엇갈림
New-Item -ItemType Directory -Force "$tools\out" | Out-Null
Copy-Item "$work\app\build\outputs\apk\release\app-release.apk" "$tools\out\sched-widget.apk" -Force
Copy-Item "$work\app\build\outputs\bundle\release\app-release.aab" "$tools\out\sched-widget.aab" -Force
$g = Get-Content "$src\app\build.gradle.kts" -Raw
$name = [regex]::Match($g, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
"테스트용 빌드 완료 (v$name): $tools\out\sched-widget.apk / .aab"