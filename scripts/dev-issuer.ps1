#requires -Version 5.1
<# Local ES256 setup. Invoke with & to configure this shell. Never prints secret values.
   A static -AttestHealthyUtc is an operator assertion, not NTP monitoring. #>
[CmdletBinding()]
param(
    [string]$KeyDirectory = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'MiezMerker/issuer'),
    [switch]$ImportFromEnvironment,
    [switch]$AttestHealthyUtc,
    [string]$CheckFirmwarePin,
    [string]$JavaExecutable
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$issuerOnWindows = $env:OS -eq 'Windows_NT'
$legacyPowerShell = $PSVersionTable.PSEdition -eq 'Desktop'
$env:TIME_BOOTSTRAP_CLOCK_HEALTHY = 'false' # Every failed/unchecked setup stays closed.
$issuerDir = [IO.Path]::GetFullPath($KeyDirectory)
$repoDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($issuerDir.Equals($repoDir, [StringComparison]::OrdinalIgnoreCase) -or
    $issuerDir.StartsWith($repoDir + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Issuer secrets must be stored outside the repository.'
}
function Protect-IssuerDirectory {
    if ($issuerOnWindows) {
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $acl.SetOwner($sid)
        $acl.SetAccessRuleProtection($true, $false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        # Set only owner/DACL, never SACL (which would require SeSecurityPrivilege).
        if ($legacyPowerShell) { [IO.DirectoryInfo]::new($issuerDir).SetAccessControl($acl) }
        else { [IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($issuerDir), $acl) }
        foreach ($file in Get-ChildItem -LiteralPath $issuerDir -File -Force) {
            $fileAcl = [Security.AccessControl.FileSecurity]::new()
            $fileAcl.SetOwner($sid)
            $fileAcl.SetAccessRuleProtection($true, $false)
            $fileAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
            if ($legacyPowerShell) { [IO.FileInfo]::new($file.FullName).SetAccessControl($fileAcl) }
            else { [IO.FileSystemAclExtensions]::SetAccessControl([IO.FileInfo]::new($file.FullName), $fileAcl) }
        }
    } else {
        [IO.File]::SetUnixFileMode($issuerDir, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
        foreach ($file in Get-ChildItem -LiteralPath $issuerDir -File -Force) {
            [IO.File]::SetUnixFileMode($file.FullName, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
        }
    }
}
# Reject links in existing ancestors and children before touching ACLs or secrets.
$ancestor = [IO.DirectoryInfo]::new($issuerDir)
while ($ancestor) {
    if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Issuer directory/ancestors must not be symlinks/reparse points.'
    }
    if (Test-Path -LiteralPath (Join-Path $ancestor.FullName '.git')) {
        throw 'Issuer secrets must not be stored inside any Git checkout.'
    }
    $ancestor = $ancestor.Parent
}
if (Test-Path -LiteralPath $issuerDir) {
    foreach ($entry in Get-ChildItem -LiteralPath $issuerDir -Force) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Issuer files must not be symlinks/reparse points.'
        }
    }
} else { [void][IO.Directory]::CreateDirectory($issuerDir) }
Protect-IssuerDirectory
$pairPath = Join-Path $issuerDir 'issuer.json'
$propertiesPath = Join-Path $issuerDir 'backend-issuer.properties'
$defaultsPath = Join-Path $issuerDir 'sdkconfig.issuer.defaults'
$issuerKey = $null; $publicKey = $null
function Invoke-LegacyIssuer([string]$private, [string]$public) {
    if (!$JavaExecutable) {
        if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
            $JavaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
        } else {
            $command = Get-Command java -ErrorAction SilentlyContinue
            if (!$command) { throw 'PowerShell 5.1 requires JDK 17+ (backend JDK 21 recommended). Set JAVA_HOME or -JavaExecutable to IntelliJ JDK bin/java.exe.' }
            $JavaExecutable = $command.Source
        }
    }
    # Secrets use a captured private pipe, never arguments, terminal output or logs.
    # Windows PowerShell can otherwise prefix redirected UTF-8 with a BOM.
    # Both protocol lines contain Base64 ASCII only; keep this function's pipe explicit.
    $OutputEncoding = [Text.Encoding]::ASCII
    $result = @(@($private, $public) | & $JavaExecutable (Join-Path $PSScriptRoot 'DevIssuer.java') 2>$null)
    if ($LASTEXITCODE -ne 0 -or $result.Count -ne 4) {
        throw 'Issuer crypto validation failed. Check JDK 17+ and the existing matching P-256 pair; no key replacement performed.'
    }
    return [pscustomobject]@{ private = $result[0]; public = $result[1]; pin = $result[2]; fingerprint = $result[3] }
}
if (!$legacyPowerShell) {
    $issuerKey = [Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
    $publicKey = [Security.Cryptography.ECDsa]::Create()
}
$lock = $null
try {
    # Serialize repeated/concurrent setup. No partial pair is ever regenerated.
    $lock = [IO.File]::Open((Join-Path $issuerDir 'setup.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if (Test-Path -LiteralPath $pairPath) {
        try { $pair = Get-Content -LiteralPath $pairPath -Raw | ConvertFrom-Json }
        catch { throw 'Existing issuer store is invalid; restore it from backup. It will not be rotated.' }
        if ($ImportFromEnvironment -and ($pair.private -cne $env:ISSUER_PRIVATE_PKCS8_B64 -or $pair.public -cne $env:ISSUER_PUBLIC_SPKI_B64)) {
            throw 'Environment issuer differs from the existing store; refusing rotation.'
        }
    } elseif ($ImportFromEnvironment) {
        if (!$env:ISSUER_PRIVATE_PKCS8_B64 -or !$env:ISSUER_PUBLIC_SPKI_B64) { throw 'Both existing issuer environment variables are required for import.' }
        $pair = [pscustomobject]@{ private = $env:ISSUER_PRIVATE_PKCS8_B64; public = $env:ISSUER_PUBLIC_SPKI_B64 }
    } else {
        if ($env:ISSUER_PRIVATE_PKCS8_B64 -or $env:ISSUER_PUBLIC_SPKI_B64 -or (Test-Path -LiteralPath $propertiesPath)) {
            throw 'Existing issuer configuration detected. Use -ImportFromEnvironment or restore issuer.json; refusing generation.'
        }
        if ($legacyPowerShell) {
            $generated = Invoke-LegacyIssuer '' ''
            $pair = [pscustomobject]@{ private = $generated.private; public = $generated.public }
        } else { $pair = [pscustomobject]@{
            private = [Convert]::ToBase64String($issuerKey.ExportPkcs8PrivateKey())
            public = [Convert]::ToBase64String($issuerKey.ExportSubjectPublicKeyInfo())
        } }
    }
    if ([string]::IsNullOrWhiteSpace($pair.private) -or [string]::IsNullOrWhiteSpace($pair.public)) {
        throw 'Existing issuer store has empty keys; restore it from backup. It will not be rotated.'
    }
    if ($legacyPowerShell) {
        $validated = Invoke-LegacyIssuer $pair.private $pair.public
        $pin = $validated.pin; $fingerprint = $validated.fingerprint
    } else {
    try {
        $privateBytes = [Convert]::FromBase64String($pair.private)
        $publicBytes = [Convert]::FromBase64String($pair.public)
        $consumed = 0
        $issuerKey.ImportPkcs8PrivateKey($privateBytes, [ref]$consumed)
        if ($consumed -ne $privateBytes.Length) { throw 'Trailing private key bytes' }
        $publicKey.ImportSubjectPublicKeyInfo($publicBytes, [ref]$consumed)
        if ($consumed -ne $publicBytes.Length) { throw 'Trailing public key bytes' }
        $parameters = $publicKey.ExportParameters($false)
        if ($parameters.Curve.Oid.Value -ne '1.2.840.10045.3.1.7' -or
            $issuerKey.ExportParameters($false).Curve.Oid.Value -ne '1.2.840.10045.3.1.7' -or
            [Convert]::ToBase64String($issuerKey.ExportSubjectPublicKeyInfo()) -cne [Convert]::ToBase64String($publicBytes)) {
            throw 'Mismatched or non-P256 pair'
        }
        $probe = [Text.Encoding]::ASCII.GetBytes('miezmerker-local-issuer-check')
        $signature = $issuerKey.SignData($probe, [Security.Cryptography.HashAlgorithmName]::SHA256)
        if (!$publicKey.VerifyData($probe, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256)) {
            throw 'Issuer pair failed signature consistency check'
        }
    } catch { throw 'Invalid or mismatched P-256 issuer pair; refusing replacement. Restore the existing pair.' }
    $point = [byte[]](@(4) + $parameters.Q.X + $parameters.Q.Y)
    $pin = [Convert]::ToHexString($point).ToLowerInvariant()
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($point)).ToLowerInvariant()
    }
    if ($CheckFirmwarePin) {
        $matches = @(Select-String -LiteralPath $CheckFirmwarePin -Pattern '^CONFIG_MM_ISSUER_PUBLIC_KEY_HEX="([0-9a-fA-F]{130})"$')
        if ($matches.Count -ne 1 -or $matches[0].Matches[0].Groups[1].Value.ToLowerInvariant() -cne $pin) {
            throw 'Firmware issuer pin missing or mismatched. Configure the generated public pin, rebuild/reflash, and compare the ESP serial fingerprint.'
        }
    }
    if (!(Test-Path -LiteralPath $pairPath)) {
        # CreateNew refuses overwrites; an interrupted/corrupt store fails on the next run.
        $stream = [IO.File]::Open($pairPath, 'CreateNew', 'Write', 'None')
        try {
            $encoded = [Text.Encoding]::UTF8.GetBytes(($pair | ConvertTo-Json -Compress))
            $stream.Write($encoded, 0, $encoded.Length); $stream.Flush($true)
        } finally { $stream.Dispose() }
    }
    [IO.File]::WriteAllText($propertiesPath, "miezmerker.issuer.private-key-pkcs8-b64=$($pair.private)`nmiezmerker.issuer.public-key-spki-b64=$($pair.public)`n")
    [IO.File]::WriteAllText($defaultsPath, "CONFIG_MM_ISSUER_PUBLIC_KEY_HEX=`"$pin`"`n")
    Protect-IssuerDirectory
    # Avoid inherited env overrides of the generated Spring properties.
    if (($env:ISSUER_PRIVATE_PKCS8_B64 -and $env:ISSUER_PRIVATE_PKCS8_B64 -cne $pair.private) -or
        ($env:ISSUER_PUBLIC_SPKI_B64 -and $env:ISSUER_PUBLIC_SPKI_B64 -cne $pair.public)) {
        throw 'Inherited issuer environment differs from the local pair. Remove overrides before starting the backend.'
    }
    $issuerLocation = ([Uri]::new($propertiesPath)).AbsoluteUri
    if (!$env:SPRING_CONFIG_ADDITIONAL_LOCATION) {
        $env:SPRING_CONFIG_ADDITIONAL_LOCATION = $issuerLocation
    } elseif ($issuerLocation -cnotin $env:SPRING_CONFIG_ADDITIONAL_LOCATION.Split(',')) {
        $env:SPRING_CONFIG_ADDITIONAL_LOCATION += ',' + $issuerLocation
    }
    $env:TIME_BOOTSTRAP_CLOCK_HEALTHY = if ($AttestHealthyUtc) { 'true' } else { 'false' }
    Write-Host "Issuer public SHA256(point)=$fingerprint"
    Write-Host "Backend configuration: $propertiesPath"
    Write-Host "Public firmware defaults: $defaultsPath"
    Write-Host "UTC operator attestation: $env:TIME_BOOTSTRAP_CLOCK_HEALTHY (no live NTP monitoring)"
} finally {
    if ($lock) { $lock.Dispose() }
    if ($issuerKey) { $issuerKey.Dispose() }; if ($publicKey) { $publicKey.Dispose() }
}
