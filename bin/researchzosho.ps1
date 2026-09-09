# ResearchZosho (研究蔵書) on Windows: the dev launcher. bin\researchzosho.cmd and bin\zosho.cmd hand off to this.
#
# Same job as bin/researchzosho: build on first use, cache the classpath in .researchzosho-cp, rebuild
# when a source file is newer than the cache, then run org.researchzosho.Main. PowerShell rather than a
# .cmd body because cmd's variables cannot hold a classpath (set /p stops at 1021 characters) and the
# service task runs the launcher through PowerShell anyway. ASCII only: PowerShell reads a BOM-less file as ANSI.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:RESEARCHZOSHO_LAUNCHER = Join-Path $repo 'bin\researchzosho.cmd'   # `service install` runs THIS launcher as the task
$cache = Join-Path $repo '.researchzosho-cp'
$java = if ($env:RESEARCHZOSHO_JAVA) { $env:RESEARCHZOSHO_JAVA } elseif ($env:CODEZAIKU_JAVA) { $env:CODEZAIKU_JAVA } else { 'java' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) {
    Write-Error "researchzosho: $java not found - ResearchZosho needs a JDK 21+ on PATH (or RESEARCHZOSHO_JAVA)"; exit 1
}
$opts = if ($env:RESEARCHZOSHO_JAVA_OPTS) { $env:RESEARCHZOSHO_JAVA_OPTS } elseif ($env:CODEZAIKU_JAVA_OPTS) { $env:CODEZAIKU_JAVA_OPTS } else { '--add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED' }

function NeedsBuild {
    if (-not (Test-Path $cache) -or (Get-Item $cache).Length -eq 0) { return $true }
    $first = (Get-Content $cache -TotalCount 1).Split(';')[0]
    if (-not $first -or -not (Test-Path $first)) { return $true }                 # absolute paths: a moved checkout
    if (-not $first.StartsWith($repo, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    $t = (Get-Item $cache).LastWriteTimeUtc
    if ((Get-Item (Join-Path $repo 'librarian\build.gradle.kts')).LastWriteTimeUtc -gt $t) { return $true }
    $newer = Get-ChildItem -Path (Join-Path $repo 'librarian\src') -Recurse -File | Where-Object { $_.LastWriteTimeUtc -gt $t } | Select-Object -First 1
    return [bool]$newer
}

if (NeedsBuild) {
    Write-Host 'researchzosho: building...' -ForegroundColor DarkGray
    # gradle finds its JVM through JAVA_HOME or PATH; the launcher may know it only through RESEARCHZOSHO_JAVA
    if (-not $env:JAVA_HOME) {
        $exe = (Get-Command $java).Source
        $homeDir = Split-Path (Split-Path $exe -Parent) -Parent
        if (Test-Path (Join-Path $homeDir 'bin\java.exe')) { $env:JAVA_HOME = $homeDir }
    }
    Push-Location $repo
    try {
        & .\gradlew.bat -q --no-daemon :librarian:classes --console=plain | Out-Host
        if ($LASTEXITCODE -ne 0) { Write-Error 'researchzosho: build failed'; exit 1 }
        $cp = (& .\gradlew.bat -q :librarian:printCp --no-daemon --console=plain) | Select-Object -Last 1
        if (-not $cp) { Write-Error 'researchzosho: classpath came back empty'; exit 1 }
        [System.IO.File]::WriteAllText($cache, $cp + "`n")
    } finally { Pop-Location }
}

$cp = (Get-Content $cache -TotalCount 1)
$optList = $opts -split ' ' | Where-Object { $_ }
& $java @optList -cp $cp org.researchzosho.Main @args
exit $LASTEXITCODE
