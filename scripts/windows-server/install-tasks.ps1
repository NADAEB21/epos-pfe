# =============================================================
# EPOS - Taches planifiees du serveur Windows
#
# Usage (PowerShell EN ADMINISTRATEUR, connecte avec le compte qui fait
# tourner Docker Desktop) :  .\install-tasks.ps1
#
#   EPOS-Start  : a l'ouverture de session -> start-epos.ps1 (attend Docker,
#                 relance la pile, verifie que l'API repond).
#   EPOS-Backup : chaque soir a 23:00 -> nightly-backup.ps1 (4 dumps,
#                 conservation 30 jours).
#
# Les deux taches tournent dans la session de l'utilisateur : Docker Desktop
# n'existe que dans une session ouverte, d'ou l'ouverture de session
# automatique (Sysinternals Autologon) sur ce poste.
# Re-executable : les taches existantes sont remplacees.
# =============================================================
param([string] $BackupTime = "23:00")

$ErrorActionPreference = "Stop"
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$ps   = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"

function Register-Epos([string] $Name, $Trigger, [string] $Script, [string] $Description) {
    $action    = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Script`""
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                 -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $Name -Action $action -Trigger $Trigger -Principal $principal `
        -Settings $settings -Description $Description -Force | Out-Null
    Write-Output "Tache $Name enregistree pour $user"
}

$logon = New-ScheduledTaskTrigger -AtLogOn -User $user
$logon.Delay = "PT1M"
Register-Epos "EPOS-Start" $logon (Join-Path $PSScriptRoot "start-epos.ps1") `
    "Demarre la pile EPOS et verifie que l'API repond (journal C:\EPOS\logs)."

Register-Epos "EPOS-Backup" (New-ScheduledTaskTrigger -Daily -At $BackupTime) (Join-Path $PSScriptRoot "nightly-backup.ps1") `
    "Sauvegarde nocturne des 4 bases EPOS dans C:\EPOS\backups (copier hors du poste apres chaque examen)."

Write-Output ""
Write-Output "Tester tout de suite :  Start-ScheduledTask EPOS-Backup ; puis voir C:\EPOS\backups et C:\EPOS\logs"
