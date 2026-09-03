$ErrorActionPreference = "Stop"

$backendDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $env:LOCALAPPDATA "LabDetect\backend-runtime\.venv\Scripts\python.exe"
$fallbackPython = Join-Path $backendDir ".runtime\.venv\Scripts\python.exe"
$codexPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"

if (-not (Test-Path -LiteralPath $pythonExe)) {
    $pythonExe = $fallbackPython
}
if (-not (Test-Path -LiteralPath $pythonExe)) {
    $pythonExe = $codexPython
}
if (-not (Test-Path -LiteralPath $pythonExe)) {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        $pythonExe = (Get-Command python).Source
    } elseif (Get-Command py -ErrorAction SilentlyContinue) {
        $pythonExe = (Get-Command py).Source
    } else {
        throw "No se encontró Python 3."
    }
}
Push-Location $backendDir
try {
    Write-Host "LabDetect IA disponible en http://0.0.0.0:8000"
    Write-Host "Mantén esta ventana abierta mientras uses la app."
    & $pythonExe (Join-Path $backendDir "server.py")
} finally {
    Pop-Location
}
