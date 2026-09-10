# ResearchZosho 0.1.2 platform pass, Windows. Args: -Dist <dir with tarball, SHA256SUMS, install.ps1> -Ver 0.1.2
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
$W = Join-Path $env:TEMP ("rzv-" + [guid]::NewGuid().ToString().Substring(0,8)); New-Item -ItemType Directory -Path "$W\www" | Out-Null
$env:RESEARCHZOSHO_PREFIX = "$W\prefix"; $env:RESEARCHZOSHO_CONFIG = "$W\config"; $env:RESEARCHZOSHO_LIBRARY = "$W\lib"
$env:RESEARCHZOSHO_JAVA = 'C:\tools\jdk25\bin\java.exe'; $env:Path = 'C:\tools\jdk25\bin;' + $env:Path
Copy-Item "$Dist\researchzosho-$Ver.tar.gz" "$W\www\"; Copy-Item "$Dist\SHA256SUMS" "$W\www\"
Copy-Item "$Dist\researchzosho-$Ver.tar.gz" "$W\www\researchzosho-$Next.tar.gz"
$h = (Get-FileHash "$W\www\researchzosho-$Next.tar.gz" -Algorithm SHA256).Hash.ToLower()
Add-Content "$W\www\SHA256SUMS" "$h  researchzosho-$Next.tar.gz"
$port = Get-Random -Minimum 20000 -Maximum 40000
$http = Start-Process -FilePath python -ArgumentList "-m http.server $port --bind 127.0.0.1" -WorkingDirectory "$W\www" -PassThru -WindowStyle Hidden
Start-Sleep 2
$env:RESEARCHZOSHO_DOWNLOAD_BASE = "http://127.0.0.1:$port"; $env:RESEARCHZOSHO_VERSION = $Ver
Write-Output "== install $Ver from install.ps1 (local server)"
$out = (& "$Dist\install.ps1" 2>&1 | Out-String); if ($LASTEXITCODE -eq 0 -or $out -match "researchzosho $Ver") { Pass 'install' } else { Fail 'install' ($out -split "`n" | Select-Object -Last 2) }
Remove-Item Env:RESEARCHZOSHO_VERSION
$Z = Get-ChildItem -Path $env:RESEARCHZOSHO_PREFIX -Recurse -Filter researchzosho.bat | Select-Object -First 1 -ExpandProperty FullName
if (-not $Z) { Fail 'find researchzosho.bat' "nothing under $env:RESEARCHZOSHO_PREFIX"; Write-Output "  $P passed, $F failed"; exit 1 }
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
Expect 'questions budget' '1 run' { & $Z questions budget 1 }
Expect 'shelf add' 'every 3 days' { & $Z shelf add gears 'antikythera gears cutting' 3 }
Expect 'shelf park' 'parked' { & $Z shelf park gears }
Expect 'tonight shows parked' 'parked' { & $Z tonight }
Expect 'shelf unpark' 'back in the rotation' { & $Z shelf unpark gears }
Expect 'inbox empty' 'nothing awaits' { & $Z inbox }
Expect 'refresh' 'refreshed' { & $Z refresh }
Expect 'update status' $Ver { & $Z update status }
Expect 'embed status' 'embeddings server' { & $Z embed status }
Write-Output "== the pages"
$port2 = Get-Random -Minimum 20000 -Maximum 40000
$srv = Start-Process -FilePath $Z -ArgumentList "serve --host 127.0.0.1 --port $port2 --log $W\serve.log" -PassThru -WindowStyle Hidden
$ok = $false; for ($i = 0; $i -lt 40 -and -not $ok; $i++) { Start-Sleep 1; try { Invoke-WebRequest "http://127.0.0.1:$port2/" -UseBasicParsing -TimeoutSec 3 | Out-Null; $ok = $true } catch {} }
Expect 'page /questions' 'Open questions' { (Invoke-WebRequest "http://127.0.0.1:$port2/questions?show=all" -UseBasicParsing).Content }
Expect 'page /questions grouped' 'Park' { (Invoke-WebRequest "http://127.0.0.1:$port2/questions" -UseBasicParsing).Content }
Expect 'page /inbox' 'Nothing awaits' { (Invoke-WebRequest "http://127.0.0.1:$port2/inbox" -UseBasicParsing).Content }
Expect 'page /jobs pause button' 'Pause the runner' { (Invoke-WebRequest "http://127.0.0.1:$port2/jobs" -UseBasicParsing).Content }
Expect 'csp allows the pick form script' 'unsafe-inline' { (Invoke-WebRequest "http://127.0.0.1:$port2/questions" -UseBasicParsing).Headers['Content-Security-Policy'] }
Expect 'http frontier filters' 'Antikythera' { (Invoke-WebRequest "http://127.0.0.1:$port2/v1/frontier" -Method Post -ContentType 'application/json' -Body '{"op":"list","q":"gears"}' -UseBasicParsing).Content }
Expect 'http inbox' 'items' { (Invoke-WebRequest "http://127.0.0.1:$port2/v1/inbox" -Method Post -ContentType 'application/json' -Body '{"op":"list"}' -UseBasicParsing).Content }
Expect 'http serials parked flag' 'parked' { (Invoke-WebRequest "http://127.0.0.1:$port2/v1/serials" -Method Post -ContentType 'application/json' -Body '{"op":"list"}' -UseBasicParsing).Content }
Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue; Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$W*" } | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep 2
Write-Output "== the service (scheduled task), and the update path with a restart"
if (schtasks /query /tn ResearchZosho 2>$null) { Write-Output '  skip service: a ResearchZosho task already exists on this box' } else {
Expect 'service install' 'installed|task|running|started' { & $Z service install }
Start-Sleep 8
Expect 'service status' 'running|Ready|Running|installed|active' { & $Z service status }
Expect "update now swaps in $Next" $Next { & $Z update now $Next }
Start-Sleep 10
Expect 'the swapped-in program answers' "$Next|$Ver" { & $Z --version }
Expect 'service came back' 'running|Ready|Running|installed|active' { & $Z service status }
Expect 'service uninstall' 'removed|uninstalled|stopped|gone' { & $Z service uninstall }
}
Stop-Process -Id $http.Id -Force -ErrorAction SilentlyContinue
Write-Output "  $P passed, $F failed   (work dir $W)"
