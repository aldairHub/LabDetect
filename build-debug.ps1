$ErrorActionPreference = "Stop"

# En este Windows el proveedor AF_UNIX del JDK anuncia compatibilidad, pero el
# controlador local rechaza la conexión. Esta variable llega también a los
# procesos Java hijos de Gradle y activa el fallback TCP del propio JDK.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\JavaTmp\af-unix-disabled"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"

& (Join-Path $PSScriptRoot "gradlew.bat") `
    --no-daemon `
    --no-configuration-cache `
    :app:assembleDebug
