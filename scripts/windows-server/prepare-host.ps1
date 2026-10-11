# =============================================================
# EPOS - Preparation du poste Windows qui sert de serveur
#
# Usage (PowerShell EN ADMINISTRATEUR) :  .\prepare-host.ps1
#
# Applique les reglages qui gardent le serveur joignable sans personne
# devant l'ecran, puis verifie ce qui ne peut pas etre regle d'ici :
#   - jamais de veille ni d'hibernation (un poste en veille = EPOS hors ligne) ;
#   - fuseau horaire de Tunis + synchronisation de l'heure : l'horloge du
#     poste est l'horloge de reference des examens (ADR-0010, ADR-0012) ;
#   - Windows Update : heures d'activite 07:00-20:00 et pas de redemarrage
#     automatique tant qu'une session est ouverte ;
#   - controles : virtualisation, port HTTP libre, SMTP Gmail sortant.
# =============================================================
param([int] $HttpPort = 80)

$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Error "Lancer ce script dans un PowerShell 'Executer en tant qu administrateur'."; exit 1 }

function Ok([string] $m)   { Write-Host "  OK      $m" -ForegroundColor Green }
function Warn([string] $m) { Write-Host "  A FAIRE $m" -ForegroundColor Yellow }

Write-Output "== Reglages =="

# Alimentation : secteur ET batterie (un portable branche peut basculer).
foreach ($k in "standby-timeout-ac", "standby-timeout-dc", "hibernate-timeout-ac", "hibernate-timeout-dc", "disk-timeout-ac") {
    powercfg /change $k 0
}
powercfg /hibernate off
Ok "veille et hibernation desactivees"

# CLDR : Africa/Tunis <-> "W. Central Africa Standard Time" (UTC+01:00).
Set-TimeZone -Id "W. Central Africa Standard Time"
Set-Service w32time -StartupType Automatic
Start-Service w32time -ErrorAction SilentlyContinue
w32tm /resync /force | Out-Null
Ok ("fuseau {0}, heure {1}" -f (Get-TimeZone).Id, (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))

$ux = "HKLM:\SOFTWARE\Microsoft\WindowsUpdate\UX\Settings"
New-Item -Path $ux -Force | Out-Null
Set-ItemProperty -Path $ux -Name ActiveHoursStart -Value 7 -Type DWord
Set-ItemProperty -Path $ux -Name ActiveHoursEnd   -Value 20 -Type DWord
$au = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU"
New-Item -Path $au -Force | Out-Null
Set-ItemProperty -Path $au -Name NoAutoRebootWithLoggedOnUsers -Value 1 -Type DWord
Ok "Windows Update : heures d'activite 07-20, pas de redemarrage auto avec session ouverte"

Write-Output ""
Write-Output "== Controles =="

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$hv  = (Get-CimInstance Win32_ComputerSystem).HypervisorPresent
if ($hv -or $cpu.VirtualizationFirmwareEnabled) { Ok "virtualisation disponible (hyperviseur present : $hv)" }
else { Warn "virtualisation non detectee : wsl --install --no-distribution ; bcdedit /set hypervisorlaunchtype Auto ; redemarrer" }

$listen = Get-NetTCPConnection -LocalPort $HttpPort -State Listen -ErrorAction SilentlyContinue |
    Where-Object { (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).Name -notmatch 'com.docker|wslrelay|vpnkit' }
if (-not $listen) { Ok "port $HttpPort libre (ou deja tenu par Docker)" }
else {
    $p = Get-Process -Id ($listen | Select-Object -First 1).OwningProcess -ErrorAction SilentlyContinue
    Warn ("port $HttpPort occupe par '{0}' (PID {1}) : arreter ce service, ou new-env.ps1 -HttpPort 8088" -f $p.Name, $p.Id)
}

$smtp = Test-NetConnection smtp.gmail.com -Port 587 -WarningAction SilentlyContinue
if ($smtp.TcpTestSucceeded) { Ok "SMTP smtp.gmail.com:587 joignable" }
else { Warn "SMTP 587 bloque par le reseau : les e-mails (invitations, reinitialisations, convocations) ne partiront pas" }

$https = Test-NetConnection login.tailscale.com -Port 443 -WarningAction SilentlyContinue
if ($https.TcpTestSucceeded) { Ok "HTTPS sortant (Tailscale) joignable" } else { Warn "HTTPS sortant bloque : le tunnel ne pourra pas s'ouvrir" }

$ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
$free = [math]::Round((Get-PSDrive C).Free / 1GB)
Ok "memoire $ram Go, disque C: $free Go libres"

Write-Output ""
Write-Output "Restent manuels : ouverture de session automatique (Sysinternals Autologon),"
Write-Output "Docker Desktop 'Start when you sign in' + mises a jour automatiques DESACTIVEES."
