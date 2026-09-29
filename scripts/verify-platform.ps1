# ResearchZosho platform pass, Windows. Args: -Dist <dir with tarball, SHA256SUMS, install.ps1> -Ver 0.5.0
# Everything the pass writes stays in its work folder: every command runs with Java's user.home there (JAVA_OPTS), so the
# state directory (the server's pid record, the logs, the service's task script) is the work folder's, never this
# machine's own. The pass calls no model: the drive is a closed port.
param([string]$Dist, [string]$Ver)
$ErrorActionPreference = 'Continue'
$parts = $Ver.Split('.'); $Next = "$($parts[0]).$($parts[1]).$([int]$parts[2] + 1)"
$script:P = 0; $script:F = 0
function Pass($n) { Write-Output "  ok   ${n}"; $script:P++ }
function Fail($n, $m) { Write-Output "  FAIL ${n}: ${m}"; $script:F++ }
function Expect($name, $want, [scriptblock]$run) {
  $out = (& $run 2>&1 | Out-String)
  if ($out -match $want) { Pass $name } else { Fail $name ("wanted '$want', got: " + ($out -split "`n" | Select-Object -Last 2) -join ' ') }
}
function Check($name, [bool]$ok, $why) { if ($ok) { Pass $name } else { Fail $name $why } }
# wait up to $seconds for a condition
function Within([int]$seconds, [scriptblock]$cond) { for ($i = 0; $i -lt $seconds; $i++) { if (& $cond) { return $true }; Start-Sleep 1 }; return [bool](& $cond) }
# the version in the manifest of the program installed at $root (its lib\librarian-*.jar)
Add-Type -AssemblyName System.IO.Compression.FileSystem
function JarVersion($root) {
  $jar = Get-ChildItem "$root\lib" -Filter 'librarian-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $jar) { return '' }
  $z = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
  try { $r = New-Object System.IO.StreamReader($z.GetEntry('META-INF/MANIFEST.MF').Open()); $t = $r.ReadToEnd(); $r.Close() } finally { $z.Dispose() }
  if ($t -match 'Implementation-Version:\s*(\S+)') { return $Matches[1] } else { return '' }
}
# what a server on $port says it is ('' when nothing answers)
function ServedVersion($port) {
  try { $c = (Invoke-WebRequest "http://127.0.0.1:$port/v1/status" -UseBasicParsing -TimeoutSec 3).Content; if ($c -match '"version":"([^"]*)"') { return $Matches[1] } } catch { }
  return ''
}
function Answers($port) { try { Invoke-WebRequest "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 3 | Out-Null; return $true } catch { return $false } }
# the update is finished when its helper has removed the download folder beside the install
function UpdateDone($prefix) { -not (Get-ChildItem $prefix -Force -Directory -Filter '.researchzosho-update-*' -ErrorAction SilentlyContinue) }
function Show($label, $text) { Write-Output "    $label"; foreach ($l in ($text -split "`r?`n")) { if ($l.Trim()) { Write-Output "      $l" } } }

$W = Join-Path $env:TEMP ("rzv-" + [guid]::NewGuid().ToString().Substring(0,8)); New-Item -ItemType Directory -Path "$W\www", "$W\home" | Out-Null
$env:RESEARCHZOSHO_PREFIX = "$W\prefix"; $env:RESEARCHZOSHO_CONFIG = "$W\config"; $env:RESEARCHZOSHO_LIBRARY = "$W\lib"
$env:JAVA_OPTS = "-Duser.home=$W\home"; $env:RESEARCHZOSHO_DRIVE = 'http://127.0.0.1:9'
$State = "$W\home\.researchzosho"
$env:RESEARCHZOSHO_JAVA = 'C:\tools\jdk25\bin\java.exe'; $env:Path = 'C:\tools\jdk25\bin;' + $env:Path
Copy-Item "$Dist\researchzosho-$Ver*.tar.gz" "$W\www\"; Copy-Item "$Dist\SHA256SUMS" "$W\www\"
# A fake next release: each tarball again, its program's manifest saying the next version, with its own checksum.
# The jar keeps its file name, so the start scripts find it; --version and the pages say the next version.
# ($asset, not $f: PowerShell variables are case-insensitive and $F is the failure counter — it was clobbered, 2026-09-11)
@'
import io, re, sys, tarfile, zipfile
src, dst, nxt = sys.argv[1:4]
with tarfile.open(src, 'r:gz') as tin, tarfile.open(dst, 'w:gz') as tout:
    for m in tin:
        data = tin.extractfile(m).read() if m.isfile() else None
        if data is not None and re.fullmatch(r'researchzosho/lib/librarian-[^/]+\.jar', m.name):
            zin = zipfile.ZipFile(io.BytesIO(data)); buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w') as zout:
                for zi in zin.infolist():
                    b = zin.read(zi)
                    if zi.filename == 'META-INF/MANIFEST.MF':
                        b = re.sub(rb'Implementation-Version: [^\r\n]*', b'Implementation-Version: ' + nxt.encode(), b)
                    zout.writestr(zi, b)
            data = buf.getvalue(); m.size = len(data)
        tout.addfile(m, io.BytesIO(data) if data is not None else None)
'@ | Set-Content "$W\fakenext.py" -Encoding ASCII
foreach ($asset in Get-ChildItem "$Dist\researchzosho-$Ver*.tar.gz") {
  $n = $asset.Name -replace [regex]::Escape($Ver), $Next
  python "$W\fakenext.py" $asset.FullName "$W\www\$n" $Next
  $h = (Get-FileHash "$W\www\$n" -Algorithm SHA256).Hash.ToLower()
  Add-Content "$W\www\SHA256SUMS" "$h  $n"
}
$port = Get-Random -Minimum 20000 -Maximum 40000
$http = Start-Process -FilePath python -ArgumentList "-m http.server $port --bind 127.0.0.1" -WorkingDirectory "$W\www" -PassThru -WindowStyle Hidden
Start-Sleep 2
$env:RESEARCHZOSHO_DOWNLOAD_BASE = "http://127.0.0.1:$port"; $env:RESEARCHZOSHO_VERSION = $Ver
Write-Output "== install $Ver from install.ps1 (local server)"
$out = (& "$Dist\install.ps1" 2>&1 | Out-String); if ($LASTEXITCODE -eq 0 -or $out -match "researchzosho $Ver") { Pass 'install' } else { Fail 'install' ($out -split "`n" | Select-Object -Last 2) }
Remove-Item Env:RESEARCHZOSHO_VERSION
$Root = "$env:RESEARCHZOSHO_PREFIX\researchzosho"
$Z = "$Root\bin\researchzosho.bat"
if (-not (Test-Path $Z)) { Fail 'find researchzosho.bat' "nothing at $Z"; Write-Output "  $P passed, $F failed"; exit 1 }
Expect 'version' $Ver { & $Z --version }
Write-Output "== a fresh library and the new verbs"
Expect 'init' 'library|initiali|ready|created' { & $Z init }
Expect 'questions add' 'queued' { & $Z questions add 'How were the gears of the Antikythera mechanism cut?' }
Expect 'questions add 2' 'queued' { & $Z questions add 'What did the 1978 lead paint ban set as the limit?' }
Expect 'questions list' 'Antikythera' { & $Z questions list }
Expect 'questions park' 'parked' { & $Z questions park 1 }
Expect 'questions list --parked' 'parked' { & $Z questions list --parked }
Expect 'questions unpark' 'back in the queue' { & $Z questions unpark 'How were the gears of the Antikythera mechanism cut?' }
Expect 'questions list --grep' '1 shown' { & $Z questions list --grep 'lead paint' }
Expect 'questions tidy' 'no duplicate' { & $Z questions tidy }
Expect 'questions budget' '1 research run' { & $Z questions budget 1 }
Write-Output "== a database: this tarball's SQLite driver keeps only the Windows native code, so open one for real"
$gz = [Convert]::FromBase64String('H4sIAAAAAAACA+3XMQrCQBAF0Nk12Mna2U6pIDZeIFEWCUbRdQtTbjBCwCjK4h28lKeysnIFbWzEVv5j/oeBucCsllnlS94eTrXzPKQ2CUExMxHJV95ESPSxfyNpcL60nsfqSmEAAAAAAAAA4GexaHaUEkfvil3pvRwbnVjNNhllmj13qw2nc6sn2vDCpLPE5DzVeZ/3ri7Z6rXtPX9zqW6k7qEAAAAAAAAA4G9EskGqiERo9wCEpOPDACAAAA==')
$ms = New-Object System.IO.MemoryStream(,$gz); $gs = New-Object System.IO.Compression.GZipStream($ms, [System.IO.Compression.CompressionMode]::Decompress)
$fs = [System.IO.File]::Create("$W\fixture.db"); $gs.CopyTo($fs); $fs.Close(); $gs.Close()
Expect 'db add (sqlite)' 'Added fixture \(SQLite\)' { & $Z db add fixture "$W\fixture.db" }
Expect 'db query' '\| 2 \|' { & $Z db query fixture 'SELECT count(*) AS n FROM t' }
Expect 'db refuses a write' 'Only queries that read' { & $Z db query fixture 'DELETE FROM t' }
Expect 'db drivers' 'PostgreSQL' { & $Z db drivers }
Expect 'db remove' 'Removed fixture' { & $Z db remove fixture }
Expect 'shelf add' 'every 3 days' { & $Z shelf add gears 'antikythera gears cutting' 3 }
Expect 'shelf park' 'parked' { & $Z shelf park gears }
Expect 'tonight shows parked' 'parked' { & $Z tonight }
Expect 'shelf unpark' 'back in the rotation' { & $Z shelf unpark gears }
Expect 'inbox empty' 'Nothing is waiting for you' { & $Z inbox }
Expect 'refresh' 'refreshed' { & $Z refresh }
Expect 'update status' $Ver { & $Z update status }
Expect 'embed status' 'embeddings server' { & $Z embed status }
Write-Output "== the pages"
$port2 = Get-Random -Minimum 20000 -Maximum 40000
$srv = Start-Process -FilePath $Z -ArgumentList "serve --host 127.0.0.1 --port $port2 --log $W\serve.log" -PassThru -WindowStyle Hidden
[void](Within 40 { Answers $port2 })
Expect 'page /questions' 'Open questions' { (Invoke-WebRequest "http://127.0.0.1:$port2/questions?show=all" -UseBasicParsing).Content }
Expect 'page /questions grouped' 'Park' { (Invoke-WebRequest "http://127.0.0.1:$port2/questions" -UseBasicParsing).Content }
Expect 'page /inbox' 'No claims are waiting' { (Invoke-WebRequest "http://127.0.0.1:$port2/inbox" -UseBasicParsing).Content }
Expect 'page /jobs pause button' 'Pause research' { (Invoke-WebRequest "http://127.0.0.1:$port2/jobs" -UseBasicParsing).Content }
Expect 'csp allows the pick form script' 'unsafe-inline' { (Invoke-WebRequest "http://127.0.0.1:$port2/questions" -UseBasicParsing).Headers['Content-Security-Policy'] }
Expect 'http frontier filters' 'Antikythera' { (Invoke-WebRequest "http://127.0.0.1:$port2/v1/frontier" -Method Post -ContentType 'application/json' -Body '{"op":"list","q":"gears"}' -UseBasicParsing).Content }
Expect 'http inbox' 'items' { (Invoke-WebRequest "http://127.0.0.1:$port2/v1/inbox" -Method Post -ContentType 'application/json' -Body '{"op":"list"}' -UseBasicParsing).Content }
Expect 'http serials parked flag' 'parked' { (Invoke-WebRequest "http://127.0.0.1:$port2/v1/serials" -Method Post -ContentType 'application/json' -Body '{"op":"list"}' -UseBasicParsing).Content }
# Start-Process on the .bat gives cmd.exe's pid; the server is the java.exe under it, and it records its own pid
$jpid = if (Test-Path "$State\researchzosho.pid") { [int](Get-Content "$State\researchzosho.pid") } else { 0 }
Check 'the server records its pid in the work folder' ($jpid -gt 0 -and (Get-Process -Id $jpid -ErrorAction SilentlyContinue).ProcessName -eq 'java') "pid record '$jpid' in $State"
if ($jpid -gt 0) { Stop-Process -Id $jpid -Force -ErrorAction SilentlyContinue }
Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue
Check 'the pages server stopped' (Within 15 { -not (Get-Process -Id $jpid -ErrorAction SilentlyContinue) -and -not (Answers $port2) }) "java pid $jpid or port $port2 still there"
Write-Output "== the service (scheduled task), and the update path with a restart"
$skip = if (schtasks /query /tn ResearchZosho 2>$null) { 'a ResearchZosho task already exists on this box' } elseif (Answers 4649) { "something already answers on the service's port 4649" } else { '' }
if ($skip) { Write-Output "  skip service: $skip" } else {
Expect 'service install' 'installed' { & $Z service install }
Check "the service serves $Ver" (Within 60 { (ServedVersion 4649) -eq $Ver }) "port 4649 says '$(ServedVersion 4649)'"
$before = if (Test-Path "$State\researchzosho.pid") { [int](Get-Content "$State\researchzosho.pid") } else { 0 }
$o = (& $Z update now $Next 2>&1 | Out-String); Show "update now ${Next}:" $o
Check "update now hands the swap over" ($LASTEXITCODE -eq 0 -and $o -match 'The update finishes when this command ends') "exit $LASTEXITCODE"
Check 'the update finishes' (Within 120 { UpdateDone $env:RESEARCHZOSHO_PREFIX }) "the download folder is still beside the install"
$jv = JarVersion $Root; Show 'installed jar after the update:' "Implementation-Version: $jv"
Check "the installed jar is $Next" ($jv -eq $Next) "the installed jar says '$jv'"
Check 'nothing is left beside the install' (-not (Get-ChildItem $env:RESEARCHZOSHO_PREFIX -Force | Where-Object { $_.Name -ne 'researchzosho' })) ((Get-ChildItem $env:RESEARCHZOSHO_PREFIX -Force | ForEach-Object { $_.Name }) -join ', ')
Expect 'the swapped-in program answers' "researchzosho $Next" { & $Z --version }
Check "the old server ($before) is gone" ($before -gt 0 -and -not (Get-Process -Id $before -ErrorAction SilentlyContinue)) "pid $before"
Check "the service came back serving $Next" (Within 90 { (ServedVersion 4649) -eq $Next }) "port 4649 says '$(ServedVersion 4649)'"
$after = if (Test-Path "$State\researchzosho.pid") { [int](Get-Content "$State\researchzosho.pid") } else { 0 }
Show 'port 4649 /v1/status after the update:' ("version " + (ServedVersion 4649) + ", server pid $after (was $before)")
if (Test-Path "$State\logs\update.log") { Show 'update.log:' (Get-Content "$State\logs\update.log" -Raw) }
# a restart: a new server process, serving again
$old = if (Test-Path "$State\researchzosho.pid") { [int](Get-Content "$State\researchzosho.pid") } else { 0 }
Expect 'service restart' 'Restarted the service' { & $Z service restart --yes }
Check "the restart started a new server (was pid $old)" (Within 90 { (Test-Path "$State\researchzosho.pid") -and ([int](Get-Content "$State\researchzosho.pid") -ne $old) -and ((ServedVersion 4649) -eq $Next) }) "pid file says '$(Get-Content "$State\researchzosho.pid" -ErrorAction SilentlyContinue)', port 4649 says '$(ServedVersion 4649)'"
$after = if (Test-Path "$State\researchzosho.pid") { [int](Get-Content "$State\researchzosho.pid") } else { 0 }
Expect 'service uninstall' 'removed' { & $Z service uninstall }
Check "the service's server stopped" (Within 20 { -not (Answers 4649) -and -not ($after -gt 0 -and (Get-Process -Id $after -ErrorAction SilentlyContinue)) }) "port 4649 or java pid $after still there"
}
Write-Output "== the build with its own Java runtime: installed on request, runs with no Java of its own, updates to its own kind"
$RT = "$W\prefix-rt"
$env:RESEARCHZOSHO_VERSION = $Ver; $env:RESEARCHZOSHO_RUNTIME = '1'; $env:RESEARCHZOSHO_PREFIX = $RT
# Write-Host lines do not reach a 2>&1 capture: judge by the exit code and by what landed on disk
$out = (& "$Dist\install.ps1" 2>&1 | Out-String); if ($LASTEXITCODE -eq 0 -and (Test-Path "$RT\researchzosho\jre\bin\java.exe")) { Pass 'install (own runtime)' } else { Fail 'install (own runtime)' ($out -split "`n" | Select-Object -Last 3) }
Remove-Item Env:RESEARCHZOSHO_VERSION; Remove-Item Env:RESEARCHZOSHO_RUNTIME; $env:RESEARCHZOSHO_PREFIX = "$W\prefix"
$Z2 = "$RT\researchzosho\bin\researchzosho.bat"
Check 'the runtime came with it' (Test-Path "$RT\researchzosho\jre\bin\java.exe") "no jre under $RT"
$env:JAVA_HOME = 'C:\nonexistent'
Expect 'version (own runtime, JAVA_HOME pointing nowhere)' $Ver { & $Z2 --version }
$env:RESEARCHZOSHO_CONFIG = "$W\config-rt"; $env:RESEARCHZOSHO_LIBRARY = "$W\lib-rt"
Expect 'init (own runtime)' 'library|initiali|ready|created' { & $Z2 init }
$o = (& $Z2 update now $Next --no-restart 2>&1 | Out-String); Show "update now $Next --no-restart (own runtime):" $o
Check 'update now hands the swap over (own runtime)' ($LASTEXITCODE -eq 0 -and $o -match 'The update finishes when this command ends') "exit $LASTEXITCODE"
Check 'the update finishes (own runtime)' (Within 120 { UpdateDone $RT }) "the download folder is still beside the install"
$jv = JarVersion "$RT\researchzosho"; Show 'installed jar after the update (own runtime):' "Implementation-Version: $jv"
Check "the installed jar is $Next (own runtime)" ($jv -eq $Next) "the installed jar says '$jv'"
Check 'the runtime is still there after the update' (Test-Path "$RT\researchzosho\jre\bin\java.exe") 'jre gone'
Expect 'version after the update (own runtime)' "researchzosho $Next" { & $Z2 --version }
Remove-Item Env:JAVA_HOME; $env:RESEARCHZOSHO_CONFIG = "$W\config"; $env:RESEARCHZOSHO_LIBRARY = "$W\lib"
Stop-Process -Id $http.Id -Force -ErrorAction SilentlyContinue
# the installer put this pass's prefix on the user PATH; take it off again, or every pass leaves a dead entry behind
# (ten of them found on the test box, 2026-09-11)
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath) { [Environment]::SetEnvironmentVariable('Path', (($userPath -split ';') | Where-Object { $_ -and -not $_.StartsWith($W) }) -join ';', 'User') }
# the work dir goes with a clean pass (a day of passes left 7 GB on the test box); a failed one keeps it for reading
if ($F -eq 0) { Set-Location $env:TEMP; Remove-Item -Recurse -Force $W -ErrorAction SilentlyContinue; Write-Output "  $P passed, $F failed" } else { Write-Output "  $P passed, $F failed   (work dir kept: $W)" }
if ($F -ne 0) { exit 1 }
