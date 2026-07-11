[CmdletBinding()]
param(
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $projectRoot ".local-dev"
$javaPidFile = Join-Path $stateDir "java-api.pid"
$frontendPidFile = Join-Path $stateDir "frontend.pid"

function Stop-ProcessTree {
    param([Parameter(Mandatory = $true)][int]$ProcessId)

    if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
        return
    }

    & taskkill.exe /PID $ProcessId /T /F *> $null
}

function Stop-TrackedProcess {
    param([Parameter(Mandatory = $true)][string]$PidFile)

    if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
        return
    }
    $processId = [System.IO.File]::ReadAllText($PidFile).Trim()
    if ($processId -match "^\d+$") {
        Stop-ProcessTree -ProcessId ([int]$processId)
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Find-ProjectProcessRoot {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [string]$ExpectedCommandPattern
    )

    $currentProcessId = $ProcessId
    $projectProcessId = $null
    for ($depth = 0; $depth -lt 12 -and $currentProcessId -gt 0; $depth++) {
        $process =
            Get-CimInstance Win32_Process -Filter "ProcessId=$currentProcessId" `
                -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            break
        }

        $commandLine = [string]$process.CommandLine
        $belongsToProject =
            $commandLine.IndexOf(
                $projectRoot,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -ge 0
        if (
            -not [string]::IsNullOrWhiteSpace($ExpectedCommandPattern) -and
            $commandLine -match $ExpectedCommandPattern
        ) {
            $belongsToProject = $true
        }
        if ($belongsToProject) {
            $projectProcessId = [int]$process.ProcessId
        }

        $currentProcessId = [int]$process.ParentProcessId
    }
    return $projectProcessId
}

function Stop-ProjectListener {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [string]$ExpectedCommandPattern
    )

    $listeners =
        Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    foreach ($listener in $listeners) {
        $projectProcessId =
            Find-ProjectProcessRoot `
                -ProcessId $listener.OwningProcess `
                -ExpectedCommandPattern $ExpectedCommandPattern
        if ($null -ne $projectProcessId) {
            Stop-ProcessTree -ProcessId $projectProcessId
        }
    }
}

function Wait-ForPortFree {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $listeners =
            Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
        if ($null -eq $listeners) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $details =
        Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        ForEach-Object {
            $process =
                Get-CimInstance Win32_Process -Filter "ProcessId=$($_.OwningProcess)" `
                    -ErrorAction SilentlyContinue
            "PID $($_.OwningProcess): $($process.Name) $($process.CommandLine)"
        }
    throw "Port $Port is still occupied. $($details -join ' | ')"
}

function Import-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file not found: $Path"
    }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }
        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1)
        [System.Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

function Wait-ForHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name,
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne $Process) {
            $Process.Refresh()
            if ($Process.HasExited) {
                throw "$Name exited before becoming ready. Exit code: $($Process.ExitCode)."
            }
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
        }
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become ready at $Url within $TimeoutSeconds seconds."
}

function Wait-ForJavaHealth {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "Java API exited before becoming ready. Exit code: $($Process.ExitCode)."
        }
        try {
            $health =
                Invoke-RestMethod `
                    -Uri "http://127.0.0.1:8080/actuator/health" `
                    -TimeoutSec 5
            if ($health.status -eq "UP") {
                return
            }
            Start-Sleep -Seconds 2
        }
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "Java API did not become healthy on port 8080 within $TimeoutSeconds seconds."
}

function Assert-ProjectListener {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$ExpectedCommandPattern
    )

    $listeners =
        Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($null -eq $listeners) {
        throw "$Name has no listener on port $Port."
    }
    foreach ($listener in $listeners) {
        $projectProcessId =
            Find-ProjectProcessRoot `
                -ProcessId $listener.OwningProcess `
                -ExpectedCommandPattern $ExpectedCommandPattern
        if ($null -ne $projectProcessId) {
            return
        }
    }
    throw "$Name listener on port $Port does not belong to this project."
}

Stop-TrackedProcess -PidFile $javaPidFile
Stop-TrackedProcess -PidFile $frontendPidFile
Stop-ProjectListener -Port 5173
Stop-ProjectListener `
    -Port 8080 `
    -ExpectedCommandPattern "com\.example\.dispute\.DisputeApplication"

if ($Stop) {
    Write-Output "Local Java API and frontend processes stopped."
    exit 0
}

& (Join-Path $PSScriptRoot "generate-secrets.ps1") | Out-Host
Import-DotEnv -Path (Join-Path $projectRoot ".env")
if (
    [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY) -or
    $env:DASHSCOPE_API_KEY -eq "__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__"
) {
    throw "DASHSCOPE_API_KEY is not configured in .env."
}
New-Item -ItemType Directory -Path $stateDir -Force | Out-Null

Push-Location $projectRoot
try {
    docker compose stop nginx java-api-service | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stop Docker nginx/java-api-service before local dev startup."
    }

    $env:JAVA_API_SERVICE_URL = "http://host.docker.internal:8080"
    docker compose up -d --no-build --force-recreate `
        python-agent-service ocr-parser-service | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start Docker Python Agent/OCR dependencies for local dev."
    }
}
finally {
    Pop-Location
}

Wait-ForPortFree -Port 5173
Wait-ForPortFree -Port 8080

$env:APP_ENV = "local"
$env:SERVER_PORT = "8080"
$env:POSTGRES_HOST = "127.0.0.1"
$env:POSTGRES_PORT = $env:POSTGRES_HOST_PORT
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = $env:REDIS_HOST_PORT
$env:MINIO_ENDPOINT = "http://127.0.0.1:$($env:MINIO_HOST_PORT)"
$env:ELASTICSEARCH_URL = "http://127.0.0.1:$($env:ELASTICSEARCH_HOST_PORT)"
$env:TEMPORAL_ADDRESS = "127.0.0.1:$($env:TEMPORAL_HOST_PORT)"
$env:PYTHON_AGENT_SERVICE_URL = "http://127.0.0.1:$($env:PYTHON_AGENT_PORT)"
$env:OCR_SERVICE_URL = "http://127.0.0.1:$($env:OCR_SERVICE_PORT)"
$env:OCR_CALLBACK_BASE_URL = "http://host.docker.internal:8080"
$env:VITE_API_PROXY_TARGET = "http://127.0.0.1:8080"
$env:VITE_AGENT_API_PROXY_TARGET = "http://127.0.0.1:$($env:PYTHON_AGENT_PORT)"
$env:VITE_OCR_API_PROXY_TARGET = "http://127.0.0.1:$($env:OCR_SERVICE_PORT)"

$javaOut = Join-Path $stateDir "java-api.out.log"
$javaErr = Join-Path $stateDir "java-api.err.log"
$frontendOut = Join-Path $stateDir "frontend.out.log"
$frontendErr = Join-Path $stateDir "frontend.err.log"

foreach ($logFile in @($javaOut, $javaErr, $frontendOut, $frontendErr)) {
    New-Item -ItemType File -Path $logFile -Force | Out-Null
    Clear-Content -LiteralPath $logFile
}

$javaProcess =
    Start-Process `
        -FilePath "cmd.exe" `
        -ArgumentList "/d", "/c", ".\mvnw.cmd -q spring-boot:run" `
        -WorkingDirectory (Join-Path $projectRoot "java-api-service") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $javaOut `
        -RedirectStandardError $javaErr `
        -PassThru
[System.IO.File]::WriteAllText($javaPidFile, $javaProcess.Id.ToString())

$frontendProcess =
    Start-Process `
        -FilePath "cmd.exe" `
        -ArgumentList "/d", "/c", "pnpm dev" `
        -WorkingDirectory (Join-Path $projectRoot "frontend") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendOut `
        -RedirectStandardError $frontendErr `
        -PassThru
[System.IO.File]::WriteAllText($frontendPidFile, $frontendProcess.Id.ToString())

try {
    Wait-ForHttp `
        -Url "http://127.0.0.1:$($env:PYTHON_AGENT_PORT)/health" `
        -Name "Python Agent"
    Wait-ForJavaHealth -Process $javaProcess
    Wait-ForHttp `
        -Url "http://127.0.0.1:5173/@vite/client" `
        -Name "Frontend" `
        -Process $frontendProcess
    Assert-ProjectListener `
        -Port 8080 `
        -Name "Java API" `
        -ExpectedCommandPattern "com\.example\.dispute\.DisputeApplication"
    Assert-ProjectListener `
        -Port 5173 `
        -Name "Frontend"
}
catch {
    Write-Error $_
    Get-Content -LiteralPath $javaErr -Tail 60 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $frontendErr -Tail 60 -ErrorAction SilentlyContinue
    throw
}

Write-Output "Local development services are ready."
Write-Output "Frontend: http://127.0.0.1:5173"
Write-Output "Java API: http://127.0.0.1:8080"
Write-Output "Stop them with: .\scripts\dev-local.ps1 -Stop"
