# 일정 위젯 APK 빌드 (Windows PowerShell)
#   powershell -ExecutionPolicy Bypass -File android\build.ps1
# - 안드로이드 빌드는 경로에 한글이 있으면 실패해서, 영문 경로로 복사해 빌드한다
# - 빌드 도구: %LOCALAPPDATA%\mytool-android (jdk, sdk, gradle)
# - 서명 키: %USERPROFILE%\.mytool\sched-release.jks, 비밀번호는 %USERPROFILE%\.gradle\gradle.properties
#   이 키가 없으면 폰에서 덮어 설치(업데이트)가 안 된다. 다른 PC에서 빌드하려면 키 파일과 비밀번호를 옮길 것
# - 결과: 저장소 app\sched-widget.apk 와 app\version.json 갱신
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
    & "$tools\gradle\bin\gradle.bat" assembleRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
} finally { Pop-Location }

$apk = "$work\app\build\outputs\apk\release\app-release.apk"
New-Item -ItemType Directory -Force "$repo\app" | Out-Null
Copy-Item $apk "$repo\app\sched-widget.apk" -Force

# 버전 번호를 app/build.gradle.kts 에서 읽어 version.json 에 기록
$g = Get-Content "$src\app\build.gradle.kts" -Raw
$code = [regex]::Match($g, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$name = [regex]::Match($g, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
[IO.File]::WriteAllText("$repo\app\version.json", "{ `"versionCode`": $code, `"versionName`": `"$name`", `"apk`": `"sched-widget.apk`" }`n", (New-Object Text.UTF8Encoding $false))
"완료: app\sched-widget.apk (v$name, code $code, $([math]::Round((Get-Item "$repo\app\sched-widget.apk").Length/1MB,2)) MB)"
