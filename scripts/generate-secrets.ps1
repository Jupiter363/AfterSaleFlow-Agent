[CmdletBinding()]
param(
    [string]$EnvFile,
    [string]$ExampleFile
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $projectRoot ".env"
}
if ([string]::IsNullOrWhiteSpace($ExampleFile)) {
    $ExampleFile = Join-Path $projectRoot ".env.example"
}

if (-not (Test-Path -LiteralPath $ExampleFile -PathType Leaf)) {
    throw "Environment template not found: $ExampleFile"
}

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    Copy-Item -LiteralPath $ExampleFile -Destination $EnvFile
}

function New-SecureHex {
    param([ValidateRange(1, 128)][int]$ByteCount)

    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return ([System.BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

function Set-GeneratedValue {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $placeholder = "$Key=__GENERATED_BY_CODEX__"
    $script:lines = $script:lines | ForEach-Object {
        if ($_ -ceq $placeholder) {
            "$Key=$Value"
        }
        else {
            $_
        }
    }
}

$lines = [System.IO.File]::ReadAllLines($EnvFile)
$existingKeys = @{}
foreach ($line in $lines) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=') {
        $existingKeys[$Matches[1]] = $true
    }
}
foreach ($line in [System.IO.File]::ReadAllLines($ExampleFile)) {
    if (
        $line -match '^([A-Za-z_][A-Za-z0-9_]*)=' -and
        -not $existingKeys.ContainsKey($Matches[1])
    ) {
        $lines += $line
        $existingKeys[$Matches[1]] = $true
    }
}

Set-GeneratedValue "POSTGRES_USER" ("user_" + (New-SecureHex 4))
Set-GeneratedValue "POSTGRES_PASSWORD" (New-SecureHex 24)
Set-GeneratedValue "REDIS_PASSWORD" (New-SecureHex 24)
Set-GeneratedValue "MINIO_ROOT_USER" ("user_" + (New-SecureHex 4))
Set-GeneratedValue "MINIO_ROOT_PASSWORD" (New-SecureHex 24)
Set-GeneratedValue "ELASTICSEARCH_PASSWORD" (New-SecureHex 24)
Set-GeneratedValue "LITELLM_MASTER_KEY" ("sk-" + (New-SecureHex 24))
Set-GeneratedValue "LITELLM_SALT_KEY" (New-SecureHex 24)
Set-GeneratedValue "LANGFUSE_PUBLIC_KEY" ("pk-lf-" + (New-SecureHex 16))
Set-GeneratedValue "LANGFUSE_SECRET_KEY" ("sk-lf-" + (New-SecureHex 24))
Set-GeneratedValue "LANGFUSE_SALT" (New-SecureHex 24)
Set-GeneratedValue "LANGFUSE_NEXTAUTH_SECRET" (New-SecureHex 24)
Set-GeneratedValue "JAVA_SERVICE_SECRET" (New-SecureHex 24)
Set-GeneratedValue "PYTHON_AGENT_SERVICE_SECRET" (New-SecureHex 24)
Set-GeneratedValue "OCR_SERVICE_SECRET" (New-SecureHex 24)

$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($EnvFile, $lines, $utf8WithoutBom)

Write-Output "Local .env generated or updated; secret values were not printed."
Write-Output "Set DASHSCOPE_API_KEY in .env before starting services."
