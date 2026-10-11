# =============================================================
# EPOS - Sauvegarde nocturne (tache planifiee "EPOS-Backup")
#
# Appelle infrastructure\sauvegarde\backup-epos.ps1 puis supprime les
# dossiers de plus de -KeepDays jours. Journal : C:\EPOS\logs.
#
# Une sauvegarde sur le disque du serveur ne protege PAS d'un disque mort :
# apres chaque journee d'examen, copier le dernier dossier hors du poste
# (cle USB chiffree / autre machine), jamais sur un partage ouvert.
# =============================================================
param(
    [string] $OutRoot  = "C:\EPOS\backups",
    [int]    $KeepDays = 30
)

$logDir = "C:\EPOS\logs"
New-Item -ItemType Directory -Force -Path $logDir, $OutRoot | Out-Null
Start-Transcript -Path (Join-Path $logDir ("backup_{0}.log" -f (Get-Date -Format "yyyy-MM-dd_HHmmss"))) | Out-Null

$script = (Resolve-Path (Join-Path $PSScriptRoot "..\..\infrastructure\sauvegarde\backup-epos.ps1")).Path
powershell -NoProfile -ExecutionPolicy Bypass -File $script -OutRoot $OutRoot
$code = $LASTEXITCODE

# Purge seulement apres une sauvegarde reussie : un echec repete ne doit
# jamais effacer les dernieres bonnes copies.
if ($code -eq 0) {
    Get-ChildItem $OutRoot -Directory |
        Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}_\d{6}$' -and $_.CreationTime -lt (Get-Date).AddDays(-$KeepDays) } |
        ForEach-Object { Write-Output "purge $($_.FullName)"; Remove-Item $_.FullName -Recurse -Force }
} else {
    Write-Output "SAUVEGARDE EN ECHEC (code $code) - aucune purge."
}

Stop-Transcript | Out-Null
exit $code
