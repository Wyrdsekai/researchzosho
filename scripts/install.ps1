# ResearchZosho one-line installer for Windows.
#
# ASCII ONLY, on purpose. Windows PowerShell 5.1 reads a BOM-less .ps1 in the system codepage, so a
# UTF-8 dash arrives as three odd characters that can break string parsing lines later.
#
#   irm https://researchzosho.org/install.ps1 | iex
#
# Downloads the release from GitHub, checks it against the release's own SHA256SUMS, and installs it.
# Only this script comes from wherever you fetched it. The program and the checksums both come from
# the same GitHub release, so this script cannot hand you something those checksums do not match.
#
#   $env:RESEARCHZOSHO_VERSION = '0.1.0'    a specific release instead of the latest
#   $env:RESEARCHZOSHO_PREFIX  = 'C:\Tools' where to install (default: %LOCALAPPDATA%\Programs)
$ErrorActionPreference = 'Stop'

$repo = 'Wyrdsekai/researchzosho'
$base = $env:RESEARCHZOSHO_DOWNLOAD_BASE        # test hook; empty means GitHub
$prefix = if ($env:RESEARCHZOSHO_PREFIX) { $env:RESEARCHZOSHO_PREFIX }
          else { Join-Path $env:LOCALAPPDATA 'Programs' }

# One plain line, not a PowerShell error record: this is the first thing a new user sees from us.
function Die($m) { [Console]::Error.WriteLine("researchzosho: $m"); exit 1 }

# Java 21 or newer on the machine gets the small tarball. Without it -- or with $env:RESEARCHZOSHO_RUNTIME set -- the
# x64 build that carries its own Java runtime is installed instead.
$javaOk = $false; $jv = 0
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    # java prints its version to stderr, which Stop treats as an error; relax it for this one call.
    $jvLine = & { $ErrorActionPreference = 'Continue'; (& java -version) 2>&1 | Select-Object -First 1 }
    $jv = "$jvLine" -replace '.*version "(\d+).*', '$1'
    if ([int]$jv -ge 21) { $javaOk = $true }
}
$runtime = ''
if ($env:RESEARCHZOSHO_RUNTIME -or -not $javaOk) {
    $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    if ("$arch" -eq 'X64') { $runtime = 'windows-x64' }
    elseif (-not $java) { Die "java not found, and there is no build with its own runtime for Windows $arch. ResearchZosho needs Java 21 or newer on PATH (https://adoptium.net)" }
    else { Die "java $jv found, and there is no build with its own runtime for Windows $arch. ResearchZosho needs 21 or newer" }
}

$ver = $env:RESEARCHZOSHO_VERSION
if (-not $ver -and -not $base) {
    $rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest"
    $ver = $rel.tag_name -replace '^v', ''
    if (-not $ver) { Die 'could not find the latest release. Set RESEARCHZOSHO_VERSION' }
}
if (-not $base) { $base = "https://github.com/$repo/releases/download/v$ver" }

$tar = "researchzosho-$ver.tar.gz"
if ($runtime) { $tar = "researchzosho-$ver-$runtime.tar.gz"; Write-Host "researchzosho: installing the $runtime build, which carries its own Java runtime" }
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("researchzosho-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "researchzosho: downloading $tar"
    try { Invoke-WebRequest "$base/$tar" -OutFile "$tmp\$tar" -UseBasicParsing }
    catch { if ($runtime) { Die "download failed: $base/$tar`n  This release may carry no build with its own runtime for $runtime. Install Java 21 or newer (https://adoptium.net) and run this again." } else { Die "download failed: $base/$tar ($_)" } }
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile "$tmp\SHA256SUMS" -UseBasicParsing

    # A download that does not match is not installed.
    $line = Get-Content "$tmp\SHA256SUMS" | Where-Object { $_ -match [regex]::Escape($tar) } | Select-Object -First 1
    if (-not $line) { Die "$tar is not listed in SHA256SUMS" }
    $expect = ($line -split '\s+')[0].ToLower()
    $actual = (Get-FileHash "$tmp\$tar" -Algorithm SHA256).Hash.ToLower()
    if ($expect -ne $actual) { Die "checksum mismatch for $tar. Refusing to install`n  expected $expect`n  got      $actual" }
    Write-Host 'researchzosho: checksum verified'

    $dest = Join-Path $prefix 'researchzosho'
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Path $prefix -Force | Out-Null
    tar -xzf "$tmp\$tar" -C $prefix          # unpacks a top-level 'researchzosho'

    $bin = Join-Path $dest 'bin'
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath -notlike "*$bin*") {
        [Environment]::SetEnvironmentVariable('Path', "$bin;$userPath", 'User')
        Write-Host "researchzosho: added $bin to your user PATH (open a new terminal to pick it up)"
    }
    Write-Host "researchzosho: installed $dest"
    & "$bin\researchzosho.bat" --version
    Write-Host "`nnext:  researchzosho setup      # a few questions, then your first document and your first answer"
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
