# =============================================================
# EPOS - Sauvegarde des trois bases (auth_db, exam_db, scoring_db)
#
# Usage :  .\backup-epos.ps1
#          .\backup-epos.ps1 -OutRoot "E:\sauvegardes-epos"
#
# Produit un dossier horodate contenant un fichier .dump par base
# (format "custom" de pg_dump, restaurable par restore-epos.ps1).
#
# UC-86 / W9 : la pile tourne sur UN poste ; une panne disque le soir
# d'un examen perd les notes. Ce script est concu pour etre lance
# chaque soir d'examen ET avant toute mise a jour, puis le dossier
# produit doit etre COPIE HORS DU POSTE (cle USB, autre machine) :
# une sauvegarde sur le meme disque ne protege pas d'un disque mort.
#
# Le script sort en erreur (code 1) si UN SEUL dump echoue ou parait
# vide : une sauvegarde partielle qui a l'air complete est pire
# qu'une absence de sauvegarde.
# =============================================================
param(
    [string]   $Container = "epos-postgres",
    [string]   $User      = "admin",
    [string[]] $Databases = @("auth_db", "exam_db", "scoring_db"),
    [string]   $OutRoot   = (Join-Path $PSScriptRoot "backups")
)

$ErrorActionPreference = "Stop"

$running = docker inspect --format "{{.State.Running}}" $Container 2>$null
if ($running -ne "true") {
    Write-Error "Le conteneur '$Container' ne tourne pas. Demarrer la pile d'abord : cd infrastructure ; docker compose up -d"
    exit 1
}

$stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$dest  = Join-Path $OutRoot $stamp
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$echec = $false
foreach ($db in $Databases) {
    $tmp   = "/tmp/epos_$db.dump"
    $local = Join-Path $dest "$db.dump"

    # pg_dump ecrit DANS le conteneur puis docker cp ramene le fichier :
    # rediriger la sortie binaire de docker exec via PowerShell corromprait
    # le dump (re-encodage du flux).
    docker exec $Container pg_dump -U $User -Fc --no-owner -f $tmp $db
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "ECHEC pg_dump pour $db"
        $echec = $true
        continue
    }
    docker cp "${Container}:${tmp}" $local | Out-Null
    docker exec $Container rm -f $tmp | Out-Null

    if (-not (Test-Path $local)) {
        Write-Warning "ECHEC docker cp pour $db"
        $echec = $true
        continue
    }
    $taille = (Get-Item $local).Length
    if ($taille -lt 1024) {
        Write-Warning "Dump suspect pour $db : $taille octets seulement"
        $echec = $true
    } else {
        Write-Output ("OK  {0,-12} {1,12:N0} octets" -f $db, $taille)
    }
}

if ($echec) {
    Write-Error "Sauvegarde INCOMPLETE - ne pas se fier au dossier $dest"
    exit 1
}

Write-Output ""
Write-Output "Sauvegarde complete : $dest"
Write-Output "PENSER A LA COPIER HORS DU POSTE (cle USB / autre machine)."
Write-Output "Ces fichiers contiennent des donnees personnelles et des empreintes"
Write-Output "de mots de passe : support chiffre ou sous cle, jamais un partage ouvert."
