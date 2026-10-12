# =============================================================
# EPOS - Fichier d'environnement du serveur Windows (poste de la faculte)
#
# Equivalent Windows de scripts/bootstrap-vps.sh : genere les secrets SUR
# le poste et ecrit C:\EPOS\epos.env, lu par docker-compose.prod.yml.
#
# Usage :
#   .\new-env.ps1 -PublicHost epos.tailXXXX.ts.net
#   .\new-env.ps1 -PublicHost epos.tailXXXX.ts.net -MailUser eposfphm@gmail.com -MailAppPassword "abcdabcdabcdabcd"
#
# -PublicHost : le nom PUBLIC (sans https://) par lequel le tunnel expose
# l'application. Il fixe l'origine CORS et le lien des e-mails "choisissez /
# reinitialisez votre mot de passe".
#
# Le tunnel (Tailscale Funnel) termine le HTTPS : Caddy sert donc du HTTP
# simple sur le port local (EPOS_DOMAIN=:80 -> aucune demande de certificat).
#
# Refuse d'ecraser un fichier existant : regenerer les secrets sous une base
# vivante casserait la connexion a Postgres (le mot de passe est fige dans le
# volume au premier demarrage).
# =============================================================
param(
    [Parameter(Mandatory = $true)]
    [string] $PublicHost,
    [string] $EnvFile         = "C:\EPOS\epos.env",
    [int]    $HttpPort        = 80,
    [string] $MailUser        = "",
    [string] $MailAppPassword = ""
)

$ErrorActionPreference = "Stop"

$PublicHost = $PublicHost.Trim() -replace '^https?://', '' -replace '/+$', ''
if ($PublicHost -notmatch '^[A-Za-z0-9.-]+$') {
    Write-Error "PublicHost invalide : '$PublicHost' (attendu : un nom d'hote, ex. epos.tail1234.ts.net)"
    exit 1
}
if (Test-Path $EnvFile) {
    Write-Error "$EnvFile existe deja : secrets CONSERVES, rien n'a ete modifie. (Le supprimer seulement avec une base vierge.)"
    exit 1
}
if (($MailUser -eq "") -ne ($MailAppPassword -eq "")) {
    Write-Error "-MailUser et -MailAppPassword vont ensemble (les deux ou aucun)."
    exit 1
}

# Alphanumerique uniquement : un "$" serait interprete par compose.
function New-Secret([int] $Length) {
    $chars = [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $rng   = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 1
    $out   = New-Object System.Text.StringBuilder
    while ($out.Length -lt $Length) {
        $rng.GetBytes($bytes)
        # 248 = 4 * 62 : rejeter au-dela evite le biais modulo.
        if ($bytes[0] -lt 248) { [void]$out.Append($chars[$bytes[0] % 62]) }
    }
    $out.ToString()
}

$mailEnabled = if ($MailUser -ne "") { "true" } else { "false" }
$mailFrom    = if ($MailUser -ne "") { $MailUser } else { "noreply@epos.tn" }
$MailAppPassword = $MailAppPassword -replace '\s', ''
$dbPassword  = New-Secret 32

# POSTGRES_USER=admin : valeur par defaut de backup-epos.ps1 / restore-epos.ps1,
# qui fonctionnent ainsi sans parametre sur ce poste.
$lines = @(
    "POSTGRES_USER=admin"
    "POSTGRES_PASSWORD=$dbPassword"
    "POSTGRES_DB=epos_master"
    "DB_USERNAME=admin"
    "DB_PASSWORD=$dbPassword"
    "AI_READER_PASSWORD=$(New-Secret 48)"
    "AI_WRITER_PASSWORD=$(New-Secret 48)"
    "JWT_SECRET=$(New-Secret 64)"
    "EPOS_DOMAIN=:80"
    "EPOS_PUBLIC_HOST=$PublicHost"
    "EPOS_HTTP_PORT=$HttpPort"
    "APP_TIMEZONE=Africa/Tunis"
    "CORS_ALLOWED_ORIGINS=https://$PublicHost"
    "LOGIN_RATE_CAPACITY=100"
    "MAIL_ENABLED=$mailEnabled"
    "MAIL_SMTP_HOST=smtp.gmail.com"
    "MAIL_SMTP_PORT=587"
    "MAIL_SMTP_AUTH=true"
    "MAIL_SMTP_STARTTLS=true"
    "MAIL_USERNAME=$MailUser"
    "MAIL_APP_PASSWORD=$MailAppPassword"
    "MAIL_FROM=$mailFrom"
    "MAIL_RESET_BASE_URL=https://$PublicHost/reset-password"
)

$dir = Split-Path -Parent $EnvFile
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# UTF-8 SANS BOM et fins de ligne LF : un BOM collerait au premier nom de
# variable (POSTGRES_USER) et compose ne le reconnaitrait plus.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($EnvFile, (($lines -join "`n") + "`n"), $utf8NoBom)

# Le fichier contient tous les secrets : lecture reservee a l'utilisateur
# courant, aux administrateurs et a SYSTEM.
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $EnvFile /inheritance:r /grant:r "${me}:F" "*S-1-5-32-544:F" "*S-1-5-18:F" | Out-Null

Write-Output "Ecrit : $EnvFile"
Write-Output "  URL publique      : https://$PublicHost"
Write-Output "  port local Caddy  : $HttpPort"
Write-Output "  messagerie        : MAIL_ENABLED=$mailEnabled"
Write-Output "Etape suivante : .\start-epos.ps1"
