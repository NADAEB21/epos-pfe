# =============================================================
# EPOS - Demarrage de la pile sur le serveur Windows
#
# Usage :  .\start-epos.ps1
#          .\start-epos.ps1 -Build        # reconstruire les images (mise a jour du code)
#
# Lance aussi par la tache planifiee "EPOS-Start" a l'ouverture de session
# (install-tasks.ps1). Les conteneurs ont restart: unless-stopped et
# repartent seuls avec Docker ; ce script est la ceinture en plus des
# bretelles, et surtout la VERIFICATION que l'application repond.
#
# Ce que fait le script :
#   1. attend le moteur Docker (lance Docker Desktop s'il ne tourne pas) ;
#   2. docker compose up -d (avec --build si une image manque ou si -Build) ;
#   3. attend que les 6 conteneurs avec healthcheck soient "healthy" ;
#   4. verifie la ROUTE complete Caddy -> passerelle -> auth-service : la
#      passerelle met ~1 min a voir les services dans Eureka apres un
#      redemarrage, un conteneur sain ne suffit donc pas.
# Sortie 1 si l'application ne repond pas dans les delais.
# =============================================================
param(
    [string] $EnvFile = "C:\EPOS\epos.env",
    [switch] $Build,
    [int]    $TimeoutMinutes = 10
)

$ErrorActionPreference = "Stop"
$infra   = (Resolve-Path (Join-Path $PSScriptRoot "..\..\infrastructure")).Path
$compose = Join-Path $infra "docker-compose.prod.yml"
$logDir  = "C:\EPOS\logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Start-Transcript -Path (Join-Path $logDir ("start-epos_{0}.log" -f (Get-Date -Format "yyyy-MM-dd_HHmmss"))) | Out-Null

function Stop-WithError([string] $Message) {
    Write-Output "ECHEC : $Message"
    Stop-Transcript | Out-Null
    exit 1
}

if (-not (Test-Path $EnvFile)) { Stop-WithError "$EnvFile introuvable - lancer d'abord new-env.ps1" }
$envMap = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; $envMap[$k] = $v
}
$port = if ($envMap["EPOS_HTTP_PORT"]) { $envMap["EPOS_HTTP_PORT"] } else { "80" }
$base = "http://localhost:$port"
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)

# 1. Moteur Docker ---------------------------------------------------------------
$ErrorActionPreference = "Continue"
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dd = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
    if (Test-Path $dd) {
        Write-Output "Docker ne repond pas : lancement de Docker Desktop..."
        Start-Process $dd
    }
    do {
        Start-Sleep -Seconds 5
        docker info *> $null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { Stop-WithError "le moteur Docker ne repond pas apres $TimeoutMinutes min" }
}
Write-Output "Moteur Docker : pret."

# 2. compose up ------------------------------------------------------------------
$images  = "epos/discovery-server", "epos/api-gateway", "epos/auth-service", "epos/exam-service",
           "epos/scoring-service", "epos/ai-service", "epos/web"
$missing = @($images | Where-Object { docker image inspect $_ *> $null; $LASTEXITCODE -ne 0 })
$composeArgs = @("compose", "--env-file", $EnvFile, "-f", $compose, "up", "-d", "--remove-orphans")
if ($Build -or $missing.Count -gt 0) {
    if ($missing.Count -gt 0) { Write-Output ("Images absentes : {0} -> construction (10-15 min la premiere fois)" -f ($missing -join ", ")) }
    $composeArgs += "--build"
}
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { Stop-WithError "docker compose up a echoue (code $LASTEXITCODE)" }

# 3. Conteneurs sains ------------------------------------------------------------
# ai-service n'a pas de healthcheck : il est verifie "en cours d'execution".
$healthy = "epos-postgres", "epos-discovery-server", "epos-api-gateway",
           "epos-auth-service", "epos-exam-service", "epos-scoring-service"
do {
    $ok = @($healthy | Where-Object { (docker inspect --format "{{.State.Health.Status}}" $_ 2>$null) -eq "healthy" }).Count
    if ($ok -lt $healthy.Count) { Start-Sleep -Seconds 5 }
} while ($ok -lt $healthy.Count -and (Get-Date) -lt $deadline)
if ($ok -lt $healthy.Count) { docker ps -a; Stop-WithError "$ok/$($healthy.Count) conteneurs sains" }
foreach ($c in "epos-ai-service", "epos-web") {
    if ((docker inspect --format "{{.State.Running}}" $c 2>$null) -ne "true") { Stop-WithError "$c ne tourne pas" }
}
Write-Output "Conteneurs : $ok/$($healthy.Count) sains, ai-service et web en cours d'execution."
$ErrorActionPreference = "Stop"

# 4. Route complete --------------------------------------------------------------
# Un identifiant inexistant : auth-service repond 4xx (route vivante) ; un 5xx
# (502/503) signifie que la passerelle ne voit pas encore le service.
$body = '{"email":"sonde-demarrage@invalid.local","password":"x"}'
$code = 0
do {
    try {
        $r = Invoke-WebRequest -Uri "$base/api/v1/auth/login" -Method Post -Body $body `
             -ContentType "application/json" -UseBasicParsing -TimeoutSec 15
        $code = [int]$r.StatusCode
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    }
    if ($code -lt 400 -or $code -ge 500) { Start-Sleep -Seconds 5 }
} while (($code -lt 400 -or $code -ge 500) -and (Get-Date) -lt $deadline)
if ($code -lt 400 -or $code -ge 500) { Stop-WithError "la route $base/api/v1/auth/login repond $code" }

Write-Output ""
Write-Output "EPOS repond localement sur $base (route d'authentification : HTTP $code)."
if ($envMap["EPOS_PUBLIC_HOST"]) { Write-Output "URL publique : https://$($envMap["EPOS_PUBLIC_HOST"])" }
Stop-Transcript | Out-Null
exit 0
