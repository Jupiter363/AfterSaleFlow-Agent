# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

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

# 业务位置：【开发与运维脚本】New-SecureHex：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
function New-SecureHex {
    param([ValidateRange(1, 128)][int]$ByteCount)

    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    # 业务位置：【开发与运维脚本】try：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
    try {
        $generator.GetBytes($bytes)
    }
    # 业务位置：【开发与运维脚本】finally：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
    finally {
        $generator.Dispose()
    }
    return ([System.BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

# 业务位置：【开发与运维脚本】Set-GeneratedValue：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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
        # 业务位置：【开发与运维脚本】else：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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
