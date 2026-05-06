$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$VersionOutput = python -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
if ($VersionOutput -notmatch '^(3\.9|3\.10|3\.11)$') {
  Write-Host "当前 Python 版本是 $VersionOutput。FunASR 在 Windows 上建议使用 Python 3.9-3.11，Python 3.13 容易触发 editdistance 等依赖源码编译失败。" -ForegroundColor Yellow
  Write-Host "请安装 Python 3.10 或 3.11 后再运行本脚本，或者使用 Dockerfile 部署 FunASR 服务。" -ForegroundColor Yellow
  exit 1
}

if ((Test-Path ".venv\Scripts\python.exe") -and -not (Test-Path ".venv\Scripts\pip.exe")) {
  Remove-Item -Recurse -Force ".venv"
}

if (-not (Test-Path ".venv\Scripts\python.exe")) {
  python -m venv .venv
}

.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn server:app --host 127.0.0.1 --port 9880
