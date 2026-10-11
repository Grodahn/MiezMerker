# Explicit local operator setup. Secrets come only from this process environment.
[CmdletBinding()]
param([switch]$Development)
$ErrorActionPreference = 'Stop'
if (-not $Development) { throw 'Pass -Development to explicitly opt in to DEMO ONLY setup.' }
if ($env:SPRING_PROFILES_ACTIVE -cne 'dev') { throw 'SPRING_PROFILES_ACTIVE must be exactly dev; production/unknown profiles are rejected.' }
if ($env:DB_URL -cnotmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1|\[::1\]):[0-9]{1,5}/[a-zA-Z0-9_-]+$') {
    throw 'DB_URL must explicitly name a loopback PostgreSQL database, including port, without URL options.'
}
foreach ($key in @('DB_USER', 'DB_PASSWORD', 'MIEZMERKER_DEMO_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key))) { throw "$key must be supplied in your local environment." }
}
$previousOptIn = $env:MIEZMERKER_DEMO_ENABLED
$repoRoot = Split-Path $PSScriptRoot -Parent
try {
    $env:MIEZMERKER_DEMO_ENABLED = 'true'
    Push-Location (Join-Path $repoRoot 'backend')
    try {
        & .\mvnw.cmd -B -ntp '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw "Backend build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
    & java -jar (Join-Path $repoRoot 'backend/target/backend-0.1.0-SNAPSHOT.jar') dev-demo
    if ($LASTEXITCODE -ne 0) { throw "Demo setup failed (exit $LASTEXITCODE). See the preceding error; no existing account is adopted or reset." }
} finally {
    $env:MIEZMERKER_DEMO_ENABLED = $previousOptIn
}
