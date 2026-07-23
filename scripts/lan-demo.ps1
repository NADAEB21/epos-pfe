# =============================================================================
# lan-demo.ps1 — Profil « démo LAN » : ce poste devient LE SERVEUR.
#
# Transforme le laptop en exactement ce que la faculté décrit par « utiliser un
# PC comme serveur » : le backend écoute sur l'adresse RÉSEAU du poste (plus
# seulement localhost), et tout appareil sur le même Wi-Fi (téléphones, autres
# PC) ouvre l'application dans son navigateur.
#
#   Usage (PowerShell, depuis la racine du repo) :
#       .\scripts\lan-demo.ps1
#
# Ce que fait le script :
#   1. Détecte l'IP locale du poste (Wi-Fi/Ethernet).
#   2. Ouvre les ports 4200/4300/8080 dans le pare-feu Windows (demande admin ;
#      si refusé, il l'affiche et continue — à faire à la main).
#   3. Recrée l'api-gateway avec un CORS élargi à cette IP (nécessaire pour
#      l'app MOBILE servie en web ; l'app WEB passe par le proxy ng et n'a
#      besoin d'aucun CORS).
#   4. Lance le web (ng serve, écoute réseau) et le mobile (Flutter web-server,
#      pointé sur l'IP) dans deux fenêtres séparées.
#   5. Affiche les URLs à ouvrir depuis les téléphones.
#
# ⚠️ Wi-Fi de faculté / eduroam : l'« isolation AP » bloque souvent le trafic
#    entre appareils. Si les téléphones ne joignent pas le poste : partage de
#    connexion (hotspot) du téléphone → tout le monde dessus → ça marche.
# ⚠️ WebSocket (scores live, port 8083) : nécessite que docker-compose publie
#    8083 en "8083:8083" (pas "127.0.0.1:8083:8083"). Sans cela, tout fonctionne
#    sauf le rafraîchissement temps réel par WS — non bloquant pour une démo.
# =============================================================================

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

# ── 1. IP locale ─────────────────────────────────────────────────────────────
# ⚠️ Exclure les cartes VIRTUELLES (vEthernet/WSL/Hyper-V/VirtualBox) : elles gagnent
# souvent au tri par metrique et leur IP (172.x) n'est PAS joignable par les telephones.
# Verifie sur ce poste : sans ce filtre, le script choisissait 172.26.208.1 (WSL).
$ip = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.IPAddress -notlike '169.254.*' -and
        $_.IPAddress -ne '127.0.0.1' -and
        $_.PrefixOrigin -ne 'WellKnown' -and
        $_.InterfaceAlias -notmatch 'vEthernet|WSL|Hyper-V|VirtualBox|Loopback|VMware'
    } |
    Sort-Object -Property InterfaceMetric |
    Select-Object -First 1).IPAddress
if (-not $ip) { Write-Host 'Aucune IP reseau trouvee - etes-vous connectee au Wi-Fi ?' -ForegroundColor Red; exit 1 }
Write-Host "IP du serveur (ce poste) : $ip" -ForegroundColor Green

# ── 2. Pare-feu (best effort) ────────────────────────────────────────────────
try {
    foreach ($p in 4200, 4300, 8080, 8083) {
        $name = "EPOS LAN demo $p"
        if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
            New-NetFirewallRule -DisplayName $name -Direction Inbound -Protocol TCP -LocalPort $p -Action Allow | Out-Null
        }
    }
    Write-Host 'Pare-feu : ports 4200/4300/8080/8083 ouverts.' -ForegroundColor Green
} catch {
    Write-Host 'Pare-feu : droits admin refuses. Ouvrez PowerShell en admin et relancez, ou autorisez les ports a la main.' -ForegroundColor Yellow
}

# ── 3. Gateway : CORS élargi à l'IP (pour l'app MOBILE servie en web) ────────
$env:CORS_ALLOWED_ORIGINS = "http://localhost:4200,http://localhost:4300,http://${ip}:4200,http://${ip}:4300"
Write-Host "CORS gateway : $env:CORS_ALLOWED_ORIGINS"
Push-Location (Join-Path $repo 'infrastructure')
docker compose up -d api-gateway | Out-Null
Pop-Location
Write-Host 'api-gateway recree avec le CORS de demo.' -ForegroundColor Green

# ── 4. Web + Mobile dans deux fenêtres ───────────────────────────────────────
# Web : URL relative /api/v1 + proxy ng -> AUCUN besoin de CORS, il suffit
# d'ecouter le reseau. --disable-host-check : ng refuse sinon les Host: <ip>.
Start-Process powershell -ArgumentList '-NoExit', '-Command',
    "cd '$repo\frontend-web'; npx ng serve --host 0.0.0.0 --disable-host-check"

# Mobile (Flutter web) : le NAVIGATEUR du telephone execute l'app, donc
# localhost = le telephone. Le define (qui GAGNE depuis #209/LAN) vise l'IP.
Start-Process powershell -ArgumentList '-NoExit', '-Command',
    "cd '$repo\epos_mobile'; flutter run -d web-server --web-hostname 0.0.0.0 --web-port 4300 --dart-define=API_BASE_URL=http://${ip}:8080/api/v1 --dart-define=WS_BASE_URL=http://${ip}:8083"

# ── 5. Les URLs ──────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '================= DEMO PRETE (une fois les 2 fenetres compilees) =================' -ForegroundColor Cyan
Write-Host " Web (responsable)  :  http://${ip}:4200" -ForegroundColor Cyan
Write-Host " Mobile (evaluateur):  http://${ip}:4300" -ForegroundColor Cyan
Write-Host ' A ouvrir depuis N IMPORTE QUEL appareil sur le meme Wi-Fi/hotspot.' -ForegroundColor Cyan
Write-Host ' Comptes : resp@epos.tn / Resp@1234  -  eval@epos.tn / Eval@1234' -ForegroundColor Cyan
Write-Host '=================================================================================='
