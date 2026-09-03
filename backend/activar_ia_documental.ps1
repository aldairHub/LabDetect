param([switch]$NonInteractive)

$ErrorActionPreference = "Stop"

$backendDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $backendDir
$envFile = Join-Path $backendDir ".env"
$runtimeDir = Join-Path $env:LOCALAPPDATA "LabDetect\backend-runtime"
$fallbackRuntimeDir = Join-Path $backendDir ".runtime"
$venvDir = Join-Path $runtimeDir ".venv"
$manualDir = Join-Path $projectDir "knowledge\manuals"
$codexPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Falta backend\.env."
}

$keyLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^OPENAI_API_KEY=sk-' } | Select-Object -First 1
if (-not $keyLine -or $keyLine.Length -lt 30) {
    throw "La clave de OpenAI no parece estar configurada en backend\.env."
}

try {
    New-Item -ItemType Directory -Force -Path $runtimeDir -ErrorAction Stop | Out-Null
} catch {
    $runtimeDir = $fallbackRuntimeDir
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
}
$venvDir = Join-Path $runtimeDir ".venv"
$pythonExe = Join-Path $venvDir "Scripts\python.exe"
if (-not (Test-Path -LiteralPath $pythonExe)) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        & py -3 -m venv $venvDir
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        & python -m venv $venvDir
    } elseif (Test-Path -LiteralPath $codexPython) {
        & $codexPython -m venv $venvDir
    } else {
        throw "No se encontró Python 3."
    }
}

Write-Host "Comprobando manuales exactos y referencias generales..."
& $pythonExe (Join-Path $backendDir "fetch_manuals.py")

$manualCount = @(Get-ChildItem -LiteralPath $manualDir -Filter *.pdf -File -Recurse -ErrorAction SilentlyContinue).Count
if ($manualCount -eq 0) {
    throw "No se descargó ningún PDF. Revisa la conexión a Internet y vuelve a ejecutar este archivo."
}

Write-Host "Indexando $manualCount PDF en OpenAI, aislados por equipo..."
& $pythonExe (Join-Path $backendDir "ingest_manuals.py")
if ($LASTEXITCODE -ne 0) { throw "La indexación no terminó correctamente." }

Write-Host ""
Write-Host "IA documental activada. Manuales locales: $manualCount"
Write-Host "Puedes cerrar esta ventana."
if (-not $NonInteractive) {
    Read-Host "Presiona Enter para terminar"
}
