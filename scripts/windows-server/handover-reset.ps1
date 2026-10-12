# =============================================================
# EPOS - Remise a zero avant la PASSATION (serveur Windows)
#
# Portage Windows de scripts/handover-reset.sh. Resultat : une base VIERGE
# - aucun examen, station, etudiant, note ni compte de test - avec UN SEUL
# compte SUPER_ADMIN. Le catalogue des matieres (referentiel d'init.sql) est
# conserve : il est necessaire pour creer les responsables.
#
# Usage :
#   .\handover-reset.ps1 -AdminEmail eposfphm@gmail.com            # explique, ne touche a rien
#   .\handover-reset.ps1 -AdminEmail eposfphm@gmail.com -Yes       # execute
#   .\handover-reset.ps1 -AdminEmail ... -AdminPassword "Xxxx1234" -Yes
#
# Sans -AdminPassword, le compte est cree SANS mot de passe et recoit le
# courriel "choisissez votre mot de passe" (valable 7 jours) : personne
# d'autre que la faculte ne connait le mot de passe. Ce mode exige
# MAIL_ENABLED=true dans epos.env (verifie AVANT toute destruction).
#
# A lancer AVANT d'ouvrir le tunnel public : les comptes de test d'init.sql
# (admin@epos.tn / Admin@1234 ...) ne doivent jamais etre en ligne.
#
# Etapes :
#   1. sauvegarde des 4 bases (backup-epos.ps1) dans C:\EPOS\backups\handover ;
#   2. arret de la pile, suppression du SEUL volume Postgres, redemarrage
#      (init.sql + Flyway sur base vierge) via start-epos.ps1 ;
#   3. creation du compte via l'API (memes gardes qu'en production), en
#      s'authentifiant avec le compte d'amorcage admin@epos.tn ;
#   4. suppression des trois comptes de test d'init.sql ;
#   5. verification : 1 utilisateur, 1 role SUPER_ADMIN, 0 examen, 0 station,
#      0 etudiant, 0 notation, matieres > 0. Sortie 1 sinon.
# =============================================================
param(
    [Parameter(Mandatory = $true)]
    [string] $AdminEmail,
    [string] $AdminPassword = "",
    [string] $EnvFile = "C:\EPOS\epos.env",
    [switch] $SkipBackup,
    [switch] $Yes
)

$ErrorActionPreference = "Stop"
$infra   = (Resolve-Path (Join-Path $PSScriptRoot "..\..\infrastructure")).Path
$compose = Join-Path $infra "docker-compose.prod.yml"

if (-not (Test-Path $EnvFile)) { Write-Error "$EnvFile introuvable : lancer d'abord new-env.ps1"; exit 1 }
$envMap = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; $envMap[$k] = $v
}
$pgu  = $envMap["POSTGRES_USER"]
$port = if ($envMap["EPOS_HTTP_PORT"]) { $envMap["EPOS_HTTP_PORT"] } else { "80" }
$base = "http://localhost:$port/api/v1"

if ($AdminPassword -eq "" -and $envMap["MAIL_ENABLED"] -ne "true") {
    Write-Output "Sans -AdminPassword, le compte est cree par invitation e-mail, or MAIL_ENABLED=$($envMap["MAIL_ENABLED"])."
    Write-Output "Activer la messagerie dans $EnvFile ou fournir -AdminPassword. Rien n'a ete touche."
    exit 1
}

Write-Output "Cette operation EFFACE toutes les donnees EPOS de ce poste et ne laisse que"
Write-Output "le compte SUPER_ADMIN $AdminEmail."
if (-not $Yes) {
    Write-Output "Relancer avec -Yes pour executer. Rien n'a ete touche."
    exit 0
}

function Invoke-Psql([string] $Db, [string] $Sql) {
    $out = docker exec epos-postgres psql -U $pgu -d $Db -v ON_ERROR_STOP=1 -tA -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql a echoue sur $Db : $Sql" }
    ($out | Out-String).Trim()
}

# 1. Sauvegarde prealable ----------------------------------------------------------
if ($SkipBackup) {
    Write-Output "==> sauvegarde IGNOREE (-SkipBackup)"
} else {
    $backup = Join-Path $infra "sauvegarde\backup-epos.ps1"
    powershell -NoProfile -ExecutionPolicy Bypass -File $backup -User $pgu -OutRoot "C:\EPOS\backups\handover"
    if ($LASTEXITCODE -ne 0) {
        Write-Output "Sauvegarde en echec : abandon AVANT destruction. Rien n'a ete touche."
        Write-Output "(Installation toute neuve sans donnees : relancer avec -SkipBackup.)"
        exit 1
    }
}

# 2. Volume Postgres neuf -----------------------------------------------------------
$ErrorActionPreference = "Continue"
Write-Output "==> arret de la pile"
docker compose --env-file $EnvFile -f $compose down
if ($LASTEXITCODE -ne 0) { Write-Output "ECHEC de docker compose down"; exit 1 }
# Nom EXACT : le projet compose de production s'appelle "epos". Une recherche
# approximative pourrait viser le volume de la pile de developpement
# (infrastructure_postgres_data) sur un poste qui a les deux.
$vol = docker volume ls --format "{{.Name}}" | Where-Object { $_ -eq "epos_postgres_data" } | Select-Object -First 1
if (-not $vol) { Write-Output "volume postgres introuvable"; exit 1 }
Write-Output "==> suppression du volume $vol"
docker volume rm $vol | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Output "ECHEC de la suppression du volume $vol"; exit 1 }
$ErrorActionPreference = "Stop"

Write-Output "==> redemarrage (init.sql + Flyway sur base vierge, ~2-3 min)"
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "start-epos.ps1") -EnvFile $EnvFile
if ($LASTEXITCODE -ne 0) { Write-Output "La pile ne repond pas apres reinitialisation - voir C:\EPOS\logs"; exit 1 }

# 3. Compte de passation via l'API --------------------------------------------------
$token = ""
for ($i = 0; $i -lt 24 -and -not $token; $i++) {
    try {
        $login = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" `
                 -Body '{"email":"admin@epos.tn","password":"Admin@1234"}' -TimeoutSec 15
        $token = $login.data.accessToken
    } catch { Start-Sleep -Seconds 5 }
}
if (-not $token) { Write-Output "impossible de se connecter avec le compte d'amorcage via $base"; exit 1 }

$payload = [ordered]@{ email = $AdminEmail; nom = "Administration"; prenom = "EPOS"; roles = @(@{ role = "SUPER_ADMIN" }) }
if ($AdminPassword) { $payload.password = $AdminPassword }
$json = $payload | ConvertTo-Json -Depth 4
try {
    $created = Invoke-WebRequest -Uri "$base/users" -Method Post -ContentType "application/json; charset=utf-8" `
               -Headers @{ Authorization = "Bearer $token" } -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
               -UseBasicParsing -TimeoutSec 40
} catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    Write-Output "creation du compte refusee (HTTP $code) : $($_.ErrorDetails.Message)"
    exit 1
}
if ([int]$created.StatusCode -ne 201) { Write-Output "creation du compte : HTTP $($created.StatusCode) (attendu 201)"; exit 1 }
Write-Output "==> compte $AdminEmail cree (SUPER_ADMIN)"
if (-not $AdminPassword) {
    $inv = ($created.Content | ConvertFrom-Json).data.invitation
    Write-Output "    invitation : $($inv | ConvertTo-Json -Compress)"
}

# 4. Suppression des comptes de test d'init.sql ---------------------------------------
$seed = "('admin@epos.tn','resp@epos.tn','eval@epos.tn')"
Invoke-Psql auth_db "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE email IN $seed);" | Out-Null
Invoke-Psql auth_db "DELETE FROM password_reset_tokens WHERE user_id IN (SELECT id FROM users WHERE email IN $seed);" | Out-Null
Invoke-Psql auth_db "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email IN $seed);" | Out-Null
Invoke-Psql auth_db "DELETE FROM users WHERE email IN $seed;" | Out-Null
Write-Output "==> comptes de test supprimes"

# 5. Verification -------------------------------------------------------------------
$fail = $false
function Test-Value([string] $Label, [string] $Actual, [string] $Expected) {
    if ($Actual -eq $Expected) { Write-Output ("  ok    {0,-24} {1}" -f $Label, $Actual) }
    else { Write-Output ("  ECHEC {0,-24} {1} (attendu {2})" -f $Label, $Actual, $Expected); $script:fail = $true }
}
Test-Value "utilisateurs"     (Invoke-Psql auth_db "SELECT count(*) FROM users;") "1"
Test-Value "e-mail du compte" (Invoke-Psql auth_db "SELECT email FROM users;") $AdminEmail
Test-Value "roles"            (Invoke-Psql auth_db "SELECT string_agg(role, ',') FROM user_roles;") "SUPER_ADMIN"
Test-Value "examens"          (Invoke-Psql exam_db "SELECT count(*) FROM examens;") "0"
Test-Value "stations"         (Invoke-Psql exam_db "SELECT count(*) FROM stations;") "0"
Test-Value "etudiants"        (Invoke-Psql scoring_db "SELECT count(*) FROM etudiants;") "0"
Test-Value "notations"        (Invoke-Psql scoring_db "SELECT count(*) FROM notations;") "0"
$m = [int](Invoke-Psql auth_db "SELECT count(*) FROM matieres;")
if ($m -ge 1) { Write-Output ("  ok    {0,-24} {1}" -f "matieres (referentiel)", $m) } else { Write-Output "  ECHEC matieres : $m"; $fail = $true }

if ($fail) {
    Write-Output "Remise a zero terminee AVEC ECARTS - la sauvegarde C:\EPOS\backups\handover permet de revenir en arriere."
    exit 1
}
Write-Output ""
Write-Output "Remise a zero terminee : EPOS ne contient plus que le compte $AdminEmail."
exit 0
