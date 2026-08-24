# =============================================================
# EPOS - Restauration des bases depuis un dossier de sauvegarde
#
# Usage :  .\restore-epos.ps1 -BackupDir .\backups\2026-08-14_180000 -Force
#          .\restore-epos.ps1 -BackupDir <dossier> -Databases scoring_db -Force
#
# DESTRUCTIF : chaque base restauree est SUPPRIMEE puis recreee depuis
# le dump. Sans -Force le script explique et ne touche a rien.
#
# Ce que fait le script, dans l'ordre :
#   1. arrete les services applicatifs (auth, exam, scoring, gateway)
#      pour que plus personne n'ecrive pendant la restauration ;
#   2. pour chaque base : coupe les connexions restantes, DROP, CREATE,
#      pg_restore depuis le dump ;
#   3. redemarre les services et attend qu'ils soient sains.
#
# Postgres lui-meme n'est PAS arrete : c'est lui qui restaure.
# Teste en round-trip complet (les 3 bases) le 2026-08-14 - voir
# docs/exploitation-sauvegarde-et-amorcage.md.
# =============================================================
param(
    [Parameter(Mandatory = $true)]
    [string]   $BackupDir,
    [string[]] $Databases,
    [string]   $Container = "epos-postgres",
    [string]   $User      = "admin",
    [switch]   $Force
)

$ErrorActionPreference = "Stop"
# ai-service (#359) : lecteur de scoring/exam + proprietaire du cache ai_db -
# il s'arrete et redemarre avec les autres. Filtre d'existence : une pile
# anterieure au module IA n'a pas ce conteneur, le script doit rester utilisable.
$services = @("epos-auth-service", "epos-exam-service", "epos-scoring-service", "epos-api-gateway", "epos-ai-service") |
    Where-Object { (docker inspect --format "{{.Name}}" $_ 2>$null) }

if (-not (Test-Path $BackupDir)) {
    Write-Error "Dossier introuvable : $BackupDir"
    exit 1
}
$dumps = Get-ChildItem $BackupDir -Filter "*.dump"
if ($Databases) {
    $dumps = $dumps | Where-Object { $Databases -contains ($_.BaseName) }
}
if (-not $dumps -or @($dumps).Count -eq 0) {
    Write-Error "Aucun fichier .dump correspondant dans $BackupDir"
    exit 1
}

Write-Output "Bases a restaurer depuis $BackupDir :"
$dumps | ForEach-Object { Write-Output ("  - {0}  ({1:N0} octets)" -f $_.BaseName, $_.Length) }

if (-not $Force) {
    Write-Output ""
    Write-Output "RESTAURATION NON LANCEE. Cette operation REMPLACE integralement les"
    Write-Output "bases listees ci-dessus par le contenu de la sauvegarde : tout ce qui"
    Write-Output "a ete saisi apres la sauvegarde sera perdu."
    Write-Output "Relancer avec -Force pour executer."
    exit 0
}

$running = docker inspect --format "{{.State.Running}}" $Container 2>$null
if ($running -ne "true") {
    Write-Error "Le conteneur '$Container' ne tourne pas. Demarrer la pile d'abord : cd infrastructure ; docker compose up -d"
    exit 1
}

Write-Output ""
Write-Output "Arret des services applicatifs..."
docker stop $services | Out-Null

$echec = $false
foreach ($dump in $dumps) {
    $db  = $dump.BaseName
    $tmp = "/tmp/epos_restore_$db.dump"
    Write-Output "Restauration de $db..."

    docker cp $dump.FullName "${Container}:${tmp}" | Out-Null

    # Couper les connexions restantes avant le DROP, sinon il echoue.
    docker exec $Container psql -U $User -d postgres -q -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid();" | Out-Null
    docker exec $Container psql -U $User -d postgres -q -c "DROP DATABASE IF EXISTS $db;"
    if ($LASTEXITCODE -ne 0) { Write-Warning "ECHEC du DROP pour $db"; $echec = $true; continue }
    docker exec $Container psql -U $User -d postgres -q -c "CREATE DATABASE $db;"
    if ($LASTEXITCODE -ne 0) { Write-Warning "ECHEC du CREATE pour $db"; $echec = $true; continue }

    docker exec $Container pg_restore -U $User --no-owner -d $db $tmp
    if ($LASTEXITCODE -ne 0) {
        # pg_restore peut sortir non-zero sur des avertissements ; le verdict
        # fiable est le comptage de tables ci-dessous, mais on le signale.
        Write-Warning "pg_restore a signale des avertissements pour $db (voir ci-dessus)"
    }
    docker exec $Container rm -f $tmp | Out-Null

    # ai_db (#359) : DROP DATABASE a emporte le GRANT CONNECT, et pg_restore
    # --no-owner rend les tables a l'admin - re-armer les droits d'ai_writer,
    # sinon ai-service ne peut plus ecrire son cache apres restauration.
    if ($db -eq "ai_db") {
        docker exec $Container psql -U $User -d postgres -q -c "DO `$`$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_writer') THEN EXECUTE 'GRANT CONNECT ON DATABASE ai_db TO ai_writer'; END IF; END `$`$;"
        docker exec $Container psql -U $User -d ai_db -q -c "DO `$`$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_writer') THEN EXECUTE 'GRANT USAGE, CREATE ON SCHEMA public TO ai_writer'; EXECUTE 'GRANT ALL ON ALL TABLES IN SCHEMA public TO ai_writer'; END IF; END `$`$;"
    }

    $tables = (docker exec $Container psql -U $User -d $db -t -A -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public';").Trim()
    if ([int]$tables -lt 1) {
        if ($db -eq "ai_db") {
            # Legitime : une sauvegarde prise avant la premiere utilisation du
            # module IA n'a aucune table (le schema est pose par ai-service au
            # premier besoin). Restaurer "rien" n'est pas un echec ici.
            Write-Output "  ai_db : 0 table (sauvegarde anterieure au premier calcul - normal)"
        } else {
            Write-Warning "ECHEC : $db ne contient aucune table apres restauration"
            $echec = $true
        }
    } else {
        Write-Output "  $db : $tables table(s) restauree(s)"
    }
}

Write-Output "Redemarrage des services applicatifs..."
docker start $services | Out-Null

Write-Output "Attente de l'etat 'healthy' (jusqu'a 120 s)..."
$limite = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 5
    $etats = docker inspect --format "{{.State.Health.Status}}" $services 2>$null
    $sains = @($etats | Where-Object { $_ -eq "healthy" }).Count
} while ($sains -lt $services.Count -and (Get-Date) -lt $limite)

if ($sains -lt $services.Count) {
    Write-Warning "Tous les services ne sont pas repasses 'healthy' - verifier : docker ps"
    $echec = $true
} else {
    Write-Output "Les $sains services applicatifs sont sains."
}

if ($echec) {
    Write-Error "Restauration terminee AVEC ERREURS - verifier les messages ci-dessus."
    exit 1
}
Write-Output ""
Write-Output "Restauration terminee. Verifier une connexion et un ecran metier avant de rendre la main."
