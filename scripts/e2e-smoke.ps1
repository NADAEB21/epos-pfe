# EPOS - end-to-end smoke test (PowerShell)
#
# Drives the running stack at $BaseUrl (default http://localhost:8080) and
# asserts the documented happy-path + key @PreAuthorize boundaries. Exits 1
# on the first failure. Intended for local dev - CI runs the JUnit/Testcontainers
# equivalent under microservices/integration-tests.
#
# Prereqs:
#   - `docker compose up -d` is running in infrastructure/
#   - JWT_SECRET in .env is the same value the stack started with
#
# Usage:
#   ./scripts/e2e-smoke.ps1                       # against http://localhost:8080
#   ./scripts/e2e-smoke.ps1 -BaseUrl http://...   # against a different gateway URL

param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$script:Failures = 0
$script:StepNumber = 0

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    $script:StepNumber++
    $label = ("[{0:D2}] {1}" -f $script:StepNumber, $Name)
    try {
        & $Action
        Write-Host "$label  PASS" -ForegroundColor Green
    } catch {
        $script:Failures++
        Write-Host "$label  FAIL" -ForegroundColor Red
        Write-Host "       $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Detail)
    if ($Expected -ne $Actual) {
        throw "Expected '$Expected', got '$Actual' - $Detail"
    }
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null,
        [int]$ExpectedStatus = 200
    )
    $uri = "$BaseUrl$Path"
    $headersWithContent = @{ "Content-Type" = "application/json" } + $Headers
    $params = @{
        Uri             = $uri
        Method          = $Method
        Headers         = $headersWithContent
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }

    # PS 5.1 doesn't have -SkipHttpErrorCheck (PS 7+ only). Invoke-WebRequest
    # throws WebException on any 4xx/5xx, and the response is hung off
    # $_.Exception.Response. Read it there and synthesise a status+body so
    # the rest of the function can treat success and expected-failure the same.
    $statusCode = $null
    $content = $null
    try {
        $response = Invoke-WebRequest @params
        $statusCode = [int]$response.StatusCode
        $content = $response.Content
    } catch [System.Net.WebException] {
        if ($_.Exception.Response -eq $null) {
            throw  # network/DNS error - re-throw, not an HTTP outcome
        }
        $statusCode = [int]$_.Exception.Response.StatusCode
        $stream = $_.Exception.Response.GetResponseStream()
        if ($stream) {
            $reader = New-Object System.IO.StreamReader($stream)
            $content = $reader.ReadToEnd()
            $reader.Close()
        }
    }

    if ($statusCode -ne $ExpectedStatus) {
        $preview = if ($content) { $content.Substring(0, [Math]::Min(300, $content.Length)) } else { "" }
        throw "HTTP $statusCode (expected $ExpectedStatus) for $Method $Path - body: $preview"
    }
    if ([string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Get-AccessToken {
    param([string]$Email, [string]$Password)
    $body = @{ email = $Email; password = $Password }
    $response = Invoke-Json -Method POST -Path "/api/v1/auth/login" -Body $body -ExpectedStatus 200
    if (-not $response.data -or -not $response.data.accessToken) {
        throw "Login response did not contain data.accessToken for $Email"
    }
    return $response.data.accessToken
}
function Auth-Header {
    param([string]$Token)
    return @{ Authorization = "Bearer $Token" }
}

# -- Gateway reachability ----------------------------------------------------

Invoke-Step "Gateway /actuator/health is 200" {
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing
        Assert-Equal 200 ([int]$r.StatusCode) "actuator health"
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            throw "actuator health returned $([int]$_.Exception.Response.StatusCode)"
        }
        throw
    }
}

# -- Login for each persona --------------------------------------------------

$adminToken = $null
$respToken  = $null
$evalToken  = $null

Invoke-Step "admin@epos.tn login -> 200 + accessToken" {
    $script:adminToken = Get-AccessToken -Email "admin@epos.tn" -Password "Admin@1234"
}

Invoke-Step "resp@epos.tn login -> 200 + accessToken" {
    $script:respToken = Get-AccessToken -Email "resp@epos.tn" -Password "Resp@1234"
}

Invoke-Step "eval@epos.tn login -> 200 + accessToken" {
    $script:evalToken = Get-AccessToken -Email "eval@epos.tn" -Password "Eval@1234"
}

Invoke-Step "Wrong password -> 401" {
    Invoke-Json -Method POST -Path "/api/v1/auth/login" `
        -Body @{ email = "admin@epos.tn"; password = "wrong" } `
        -ExpectedStatus 401 | Out-Null
}

# -- auth-service: /users RBAC -----------------------------------------------

Invoke-Step "GET /api/v1/users as admin -> 200" {
    Invoke-Json -Method GET -Path "/api/v1/users" `
        -Headers (Auth-Header $script:adminToken) -ExpectedStatus 200 | Out-Null
}

Invoke-Step "GET /api/v1/users as resp -> 200" {
    Invoke-Json -Method GET -Path "/api/v1/users" `
        -Headers (Auth-Header $script:respToken) -ExpectedStatus 200 | Out-Null
}

Invoke-Step "GET /api/v1/users as eval -> 403" {
    Invoke-Json -Method GET -Path "/api/v1/users" `
        -Headers (Auth-Header $script:evalToken) -ExpectedStatus 403 | Out-Null
}

Invoke-Step "GET /api/v1/users with no token -> 401" {
    Invoke-Json -Method GET -Path "/api/v1/users" -ExpectedStatus 401 | Out-Null
}

# -- auth-service: /matieres -------------------------------------------------

Invoke-Step "GET /api/v1/matieres as eval -> 200 (any authenticated)" {
    $r = Invoke-Json -Method GET -Path "/api/v1/matieres" `
        -Headers (Auth-Header $script:evalToken) -ExpectedStatus 200
    if (-not $r) { throw "Empty matieres response" }
}

# -- exam-service: examen create + list --------------------------------------

Invoke-Step "POST /api/v1/examens as resp (matiereId=1) -> 201" {
    $body = @{
        nom         = "E2E Smoke Examen $(Get-Date -Format yyyyMMddHHmmss)"
        description = "Generated by scripts/e2e-smoke.ps1"
        dateExamen  = (Get-Date).AddDays(7).ToString("yyyy-MM-dd")
        matiereId   = 1
    }
    Invoke-Json -Method POST -Path "/api/v1/examens" `
        -Headers (Auth-Header $script:respToken) -Body $body -ExpectedStatus 201 | Out-Null
}

Invoke-Step "POST /api/v1/examens as resp (matiereId=2) -> 403 (out of scope)" {
    $body = @{
        nom         = "Out of scope examen"
        description = "Should be rejected"
        dateExamen  = (Get-Date).AddDays(7).ToString("yyyy-MM-dd")
        matiereId   = 2
    }
    Invoke-Json -Method POST -Path "/api/v1/examens" `
        -Headers (Auth-Header $script:respToken) -Body $body -ExpectedStatus 403 | Out-Null
}

Invoke-Step "GET /api/v1/examens as resp -> 200" {
    Invoke-Json -Method GET -Path "/api/v1/examens" `
        -Headers (Auth-Header $script:respToken) -ExpectedStatus 200 | Out-Null
}

Invoke-Step "GET /api/v1/examens as eval -> 403 (class-level @PreAuthorize)" {
    Invoke-Json -Method GET -Path "/api/v1/examens" `
        -Headers (Auth-Header $script:evalToken) -ExpectedStatus 403 | Out-Null
}

# -- scoring-service: etudiants ----------------------------------------------

Invoke-Step "GET /api/v1/etudiants as admin -> 200" {
    Invoke-Json -Method GET -Path "/api/v1/etudiants" `
        -Headers (Auth-Header $script:adminToken) -ExpectedStatus 200 | Out-Null
}

Invoke-Step "GET /api/v1/etudiants as eval -> 200 (evaluator may read students)" {
    Invoke-Json -Method GET -Path "/api/v1/etudiants" `
        -Headers (Auth-Header $script:evalToken) -ExpectedStatus 200 | Out-Null
}

# -- scoring-service: EVALUATEUR notation scope (#85, #91, ADR 0007) ---------
# An evaluateur may lock/modify only notations attached to a rotation whose
# evaluateurId is their own user id. Build two chains as admin (unrestricted):
# rotation -> assignment -> notation, one owned by eval and one foreign. Then
# prove eval can lock the owned notation (200) but not the foreign one (403).

function Get-JwtClaim {
    param([string]$Token, [string]$Claim)
    $payload = $Token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) { 2 { $payload += '==' } 3 { $payload += '=' } }
    $json = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($payload))
    return ($json | ConvertFrom-Json).$Claim
}

$script:ownNotationId     = $null
$script:foreignNotationId = $null

Invoke-Step "Build eval-owned + foreign notation chains as admin -> 201" {
    $evalUserId = Get-JwtClaim -Token $script:evalToken -Claim "userId"
    if (-not $evalUserId) { throw "Could not read userId claim from eval token" }
    $foreignEvalId = [int64]$evalUserId + 999999
    $adminHdr = Auth-Header $script:adminToken

    $rotOwn = Invoke-Json -Method POST -Path "/api/v1/rotations" -Headers $adminHdr -ExpectedStatus 201 `
        -Body @{ evaluateurId = $evalUserId; stationId = 1; ordrePassage = 1; statut = "EN_ATTENTE" }
    $rotForeign = Invoke-Json -Method POST -Path "/api/v1/rotations" -Headers $adminHdr -ExpectedStatus 201 `
        -Body @{ evaluateurId = $foreignEvalId; stationId = 1; ordrePassage = 2; statut = "EN_ATTENTE" }

    $asgOwn = Invoke-Json -Method POST -Path "/api/v1/assignments" -Headers $adminHdr -ExpectedStatus 201 `
        -Body @{ rotationId = $rotOwn.data.id; presenceConfirmee = $false; tempsAdditionnel = 0 }
    $asgForeign = Invoke-Json -Method POST -Path "/api/v1/assignments" -Headers $adminHdr -ExpectedStatus 201 `
        -Body @{ rotationId = $rotForeign.data.id; presenceConfirmee = $false; tempsAdditionnel = 0 }

    $notOwn = Invoke-Json -Method POST -Path "/api/v1/notations" -Headers $adminHdr -ExpectedStatus 201 `
        -Body @{ score_final = 12.0; temps_additionnel = 0; stationId = 1; grilleId = 1; assignmentId = $asgOwn.data.id }
    $notForeign = Invoke-Json -Method POST -Path "/api/v1/notations" -Headers $adminHdr -ExpectedStatus 201 `
        -Body @{ score_final = 12.0; temps_additionnel = 0; stationId = 1; grilleId = 1; assignmentId = $asgForeign.data.id }

    $script:ownNotationId     = $notOwn.data.id
    $script:foreignNotationId = $notForeign.data.id
    if (-not $script:ownNotationId -or -not $script:foreignNotationId) {
        throw "Notation ids not returned by create"
    }
}

Invoke-Step "PATCH own notation /verrouiller as eval -> 200 (#85)" {
    Invoke-Json -Method PATCH -Path "/api/v1/notations/$($script:ownNotationId)/verrouiller" `
        -Headers (Auth-Header $script:evalToken) -ExpectedStatus 200 | Out-Null
}

Invoke-Step "PATCH foreign notation /verrouiller as eval -> 403 (#85)" {
    Invoke-Json -Method PATCH -Path "/api/v1/notations/$($script:foreignNotationId)/verrouiller" `
        -Headers (Auth-Header $script:evalToken) -ExpectedStatus 403 | Out-Null
}

# -- CORS preflight on protected path ----------------------------------------

Invoke-Step "OPTIONS preflight from http://localhost:4200 -> 200 + Allow-Origin" {
    $headers = @{
        "Origin"                         = "http://localhost:4200"
        "Access-Control-Request-Method"  = "GET"
        "Access-Control-Request-Headers" = "authorization,content-type"
    }
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl/api/v1/users" -Method Options -Headers $headers -UseBasicParsing
        Assert-Equal 200 ([int]$r.StatusCode) "preflight status"
        $allowOrigin = $r.Headers["Access-Control-Allow-Origin"]
        if ($allowOrigin -ne "http://localhost:4200") {
            throw "Access-Control-Allow-Origin = '$allowOrigin', expected 'http://localhost:4200'"
        }
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            throw "preflight returned $([int]$_.Exception.Response.StatusCode)"
        }
        throw
    }
}

# -- Real POST through the gateway: must carry exactly one Allow-Origin ------
# The OPTIONS check above is short-circuited by the gateway's CorsWebFilter
# and never reaches the backend. Past regression: backend services also set
# their own Allow-Origin, doubling the header on the real POST and breaking
# browsers while this smoke stayed green. This step guards against that.

Invoke-Step "POST /api/v1/auth/login with Origin -> exactly one Allow-Origin" {
    $headers = @{
        "Origin"       = "http://localhost:4200"
        "Content-Type" = "application/json"
    }
    $body = @{ email = "admin@epos.tn"; password = "Admin@1234" } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post `
        -Headers $headers -Body $body -UseBasicParsing
    Assert-Equal 200 ([int]$r.StatusCode) "login status"
    $allowOrigin = $r.Headers["Access-Control-Allow-Origin"]
    # PowerShell merges duplicate header fields into a comma-joined string —
    # a comma in this value means two Allow-Origin fields hit the wire.
    if (-not $allowOrigin -or $allowOrigin -match ",") {
        throw "Expected one Access-Control-Allow-Origin: 'http://localhost:4200', got '$allowOrigin'"
    }
    if ($allowOrigin -ne "http://localhost:4200") {
        throw "Access-Control-Allow-Origin = '$allowOrigin', expected 'http://localhost:4200'"
    }
}

# -- Summary -----------------------------------------------------------------

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "All $script:StepNumber steps passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$script:Failures of $script:StepNumber steps failed." -ForegroundColor Red
    exit 1
}