# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

[CmdletBinding()]
param(
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$stateDir = Join-Path $projectRoot ".local-dev"
$javaPidFile = Join-Path $stateDir "java-api.pid"
$javaControlWorkerPidFile = Join-Path $stateDir "java-control-worker.pid"
$javaAgentWorkerPidFile = Join-Path $stateDir "java-agent-worker.pid"
$frontendPidFile = Join-Path $stateDir "frontend.pid"
$pythonAgentPidFile = Join-Path $stateDir "python-agent.pid"

# 业务位置：【开发与运维脚本】Stop-ProcessTree：启动或关闭与 当前阶段业务数据 相关的后台任务或订阅，控制运行资源和生命周期。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
function Stop-ProcessTree {
    param([Parameter(Mandatory = $true)][int]$ProcessId)

    if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
        return
    }

    & taskkill.exe /PID $ProcessId /T /F *> $null
}

# 业务位置：【开发与运维脚本】Stop-TrackedProcess：启动或关闭与 当前阶段业务数据 相关的后台任务或订阅，控制运行资源和生命周期。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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

# 业务位置：【开发与运维脚本】Find-ProjectProcessRoot：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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

# 业务位置：【开发与运维脚本】Stop-ProjectListener：启动或关闭与 当前阶段业务数据 相关的后台任务或订阅，控制运行资源和生命周期。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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

# 业务位置：【开发与运维脚本】Wait-ForPortFree：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
function Wait-ForPortFree {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    # 业务位置：【开发与运维脚本】do：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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
        # 业务位置：【开发与运维脚本】ForEach-Object：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
        ForEach-Object {
            $process =
                Get-CimInstance Win32_Process -Filter "ProcessId=$($_.OwningProcess)" `
                    -ErrorAction SilentlyContinue
            "PID $($_.OwningProcess): $($process.Name) $($process.CommandLine)"
        }
    throw "Port $Port is still occupied. $($details -join ' | ')"
}

# 业务位置：【开发与运维脚本】Import-DotEnv：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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

# 业务位置：【开发与运维脚本】Wait-ForHttp：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
function Wait-ForHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name,
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    # 业务位置：【开发与运维脚本】do：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
    do {
        if ($null -ne $Process) {
            $Process.Refresh()
            if ($Process.HasExited) {
                throw "$Name exited before becoming ready. Exit code: $($Process.ExitCode)."
            }
        }
        # 业务位置：【开发与运维脚本】try：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
        }
        # 业务位置：【开发与运维脚本】catch：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become ready at $Url within $TimeoutSeconds seconds."
}

# 业务位置：【开发与运维脚本】Wait-ForJavaHealth：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
function Wait-ForJavaHealth {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    # 业务位置：【开发与运维脚本】do：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
    do {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "Java API exited before becoming ready. Exit code: $($Process.ExitCode)."
        }
        # 业务位置：【开发与运维脚本】try：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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
        # 业务位置：【开发与运维脚本】catch：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "Java API did not become healthy on port 8080 within $TimeoutSeconds seconds."
}

function Wait-ForProcessLog {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][string]$LogFile,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Name,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "$Name exited before becoming ready. Exit code: $($Process.ExitCode)."
        }
        $content = Get-Content -LiteralPath $LogFile -Raw -ErrorAction SilentlyContinue
        if ($null -ne $content -and $content -match $Pattern) {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not report readiness within $TimeoutSeconds seconds."
}

# 业务位置：【开发与运维脚本】Assert-ProjectListener：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
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
Stop-TrackedProcess -PidFile $javaControlWorkerPidFile
Stop-TrackedProcess -PidFile $javaAgentWorkerPidFile
Stop-TrackedProcess -PidFile $frontendPidFile
Stop-TrackedProcess -PidFile $pythonAgentPidFile
Stop-ProjectListener -Port 5173
Stop-ProjectListener `
    -Port 8080 `
    -ExpectedCommandPattern "com\.example\.dispute\.DisputeApplication"
Stop-ProjectListener `
    -Port 18000 `
    -ExpectedCommandPattern "uvicorn|python-agent-service|app\.main:create_app"

if ($Stop) {
    Write-Output "Local Java API, Temporal workers, Python Agent, and frontend processes stopped."
    exit 0
}

& (Join-Path $PSScriptRoot "generate-secrets.ps1") | Out-Host
Import-DotEnv -Path (Join-Path $projectRoot ".env")
if (
    [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY) -or
    $env:DASHSCOPE_API_KEY -eq "EMPTY" -or
    $env:DASHSCOPE_API_KEY -eq "__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__"
) {
    Write-Warning (
        "DASHSCOPE_API_KEY is not configured in .env. Java and frontend will start, " +
        "but model-backed Agent requests will fail closed until the key is configured."
    )
}
New-Item -ItemType Directory -Path $stateDir -Force | Out-Null

Push-Location $projectRoot
# 业务位置：【开发与运维脚本】try：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
try {
    docker compose stop nginx java-api-service java-control-worker java-agent-worker python-agent-service | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stop Docker application processes before local dev startup."
    }

    $env:JAVA_API_SERVICE_URL = "http://host.docker.internal:8080"
    docker compose up -d --no-build --force-recreate `
        ocr-parser-service | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start Docker OCR dependencies for local dev."
    }
}
# 业务位置：【开发与运维脚本】finally：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
finally {
    Pop-Location
}

Wait-ForPortFree -Port 5173
Wait-ForPortFree -Port 8080
Wait-ForPortFree -Port 18000

$env:APP_ENV = "local"
$env:SERVER_PORT = "8080"
$env:POSTGRES_HOST = "127.0.0.1"
$env:POSTGRES_PORT = $env:POSTGRES_HOST_PORT
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = $env:REDIS_HOST_PORT
$env:MINIO_ENDPOINT = "http://127.0.0.1:$($env:MINIO_HOST_PORT)"
$env:ELASTICSEARCH_URL = "http://127.0.0.1:$($env:ELASTICSEARCH_HOST_PORT)"
$env:TEMPORAL_ADDRESS = "127.0.0.1:$($env:TEMPORAL_HOST_PORT)"
$env:APP_TEMPORAL_WORKER_ENABLED = "false"
$env:PYTHON_AGENT_SERVICE_URL = "http://127.0.0.1:$($env:PYTHON_AGENT_PORT)"
$env:OCR_SERVICE_URL = "http://127.0.0.1:$($env:OCR_SERVICE_PORT)"
$env:OCR_CALLBACK_BASE_URL = "http://host.docker.internal:8080"
$litellmPort = if ([string]::IsNullOrWhiteSpace($env:LITELLM_PORT)) { "14000" } else { $env:LITELLM_PORT }
$langfusePort = if ([string]::IsNullOrWhiteSpace($env:LANGFUSE_PORT)) { "13000" } else { $env:LANGFUSE_PORT }
$env:LITELLM_BASE_URL = "http://127.0.0.1:$litellmPort"
$env:LANGFUSE_HOST = "http://127.0.0.1:$langfusePort"
$env:JAVA_API_SERVICE_URL = "http://127.0.0.1:8080"
$env:VITE_API_PROXY_TARGET = "http://127.0.0.1:8080"
$env:VITE_AGENT_API_PROXY_TARGET = "http://127.0.0.1:$($env:PYTHON_AGENT_PORT)"
$env:VITE_OCR_API_PROXY_TARGET = "http://127.0.0.1:$($env:OCR_SERVICE_PORT)"

$javaOut = Join-Path $stateDir "java-api.out.log"
$javaErr = Join-Path $stateDir "java-api.err.log"
$javaControlWorkerOut = Join-Path $stateDir "java-control-worker.out.log"
$javaControlWorkerErr = Join-Path $stateDir "java-control-worker.err.log"
$javaAgentWorkerOut = Join-Path $stateDir "java-agent-worker.out.log"
$javaAgentWorkerErr = Join-Path $stateDir "java-agent-worker.err.log"
$frontendOut = Join-Path $stateDir "frontend.out.log"
$frontendErr = Join-Path $stateDir "frontend.err.log"
$pythonAgentOut = Join-Path $stateDir "python-agent.out.log"
$pythonAgentErr = Join-Path $stateDir "python-agent.err.log"

foreach (
    $logFile in @(
        $javaOut,
        $javaErr,
        $javaControlWorkerOut,
        $javaControlWorkerErr,
        $javaAgentWorkerOut,
        $javaAgentWorkerErr,
        $frontendOut,
        $frontendErr,
        $pythonAgentOut,
        $pythonAgentErr
    )
) {
    New-Item -ItemType File -Path $logFile -Force | Out-Null
    Clear-Content -LiteralPath $logFile
}

$javaServiceDir = Join-Path $projectRoot "apps/domain-service"
$mavenCommand = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
$mavenExecutable =
    if ($null -ne $mavenCommand) {
        $mavenCommand.Source
    }
    else {
        Join-Path $javaServiceDir "mvnw.cmd"
    }

Push-Location $javaServiceDir
try {
    & $mavenExecutable -q -Dmaven.test.skip=true package
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to build the shared Java runtime before local startup."
    }
}
finally {
    Pop-Location
}

$javaJar =
    Get-ChildItem -LiteralPath (Join-Path $javaServiceDir "target") `
        -Filter "java-api-service-*.jar" |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $javaJar) {
    throw "Built Java runtime JAR was not found."
}
$javaExecutable = (Get-Command "java.exe" -ErrorAction Stop).Source
$workerVersioningMode =
    if ([string]::IsNullOrWhiteSpace($env:TEMPORAL_WORKER_VERSIONING_MODE)) {
        "NONE"
    }
    else {
        $env:TEMPORAL_WORKER_VERSIONING_MODE
    }
$controlDeploymentName =
    if ([string]::IsNullOrWhiteSpace($env:TEMPORAL_CONTROL_DEPLOYMENT_NAME)) {
        "after-sale-control"
    }
    else {
        $env:TEMPORAL_CONTROL_DEPLOYMENT_NAME
    }
$controlBuildId =
    if ([string]::IsNullOrWhiteSpace($env:TEMPORAL_CONTROL_BUILD_ID)) {
        "local-dev"
    }
    else {
        $env:TEMPORAL_CONTROL_BUILD_ID
    }
$agentDeploymentName =
    if ([string]::IsNullOrWhiteSpace($env:TEMPORAL_AGENT_DEPLOYMENT_NAME)) {
        "after-sale-agent"
    }
    else {
        $env:TEMPORAL_AGENT_DEPLOYMENT_NAME
    }
$agentBuildId =
    if ([string]::IsNullOrWhiteSpace($env:TEMPORAL_AGENT_BUILD_ID)) {
        "local-dev"
    }
    else {
        $env:TEMPORAL_AGENT_BUILD_ID
    }

$pythonExe = $env:PYTHON_EXE
if ([string]::IsNullOrWhiteSpace($pythonExe)) {
    $minicondaPython = "D:\miniconda\python.exe"
    if (Test-Path -LiteralPath $minicondaPython -PathType Leaf) {
        $pythonExe = $minicondaPython
    }
    # 业务位置：【开发与运维脚本】else：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
    else {
        $pythonExe = (Get-Command "python.exe" -ErrorAction Stop).Source
    }
}

$pythonAgentProcess =
    Start-Process `
        -FilePath $pythonExe `
        -ArgumentList "-m", "uvicorn", "app.main:create_app", "--factory", "--host", "127.0.0.1", "--port", $env:PYTHON_AGENT_PORT, "--reload" `
        -WorkingDirectory (Join-Path $projectRoot "apps/agent-runtime") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $pythonAgentOut `
        -RedirectStandardError $pythonAgentErr `
        -PassThru
[System.IO.File]::WriteAllText($pythonAgentPidFile, $pythonAgentProcess.Id.ToString())

$javaProcess =
    Start-Process `
        -FilePath $javaExecutable `
        -ArgumentList "-Xms128m", "-Xmx1024m", "-XX:+ExitOnOutOfMemoryError", "-jar", $javaJar.FullName, "--spring.profiles.active=local,api", "--app.temporal.worker.enabled=false" `
        -WorkingDirectory $javaServiceDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $javaOut `
        -RedirectStandardError $javaErr `
        -PassThru
[System.IO.File]::WriteAllText($javaPidFile, $javaProcess.Id.ToString())

$javaControlWorkerProcess =
    Start-Process `
        -FilePath $javaExecutable `
        -ArgumentList "-Xms128m", "-Xmx1536m", "-XX:+ExitOnOutOfMemoryError", "-jar", $javaJar.FullName, "--spring.profiles.active=local,control-worker", "--app.temporal.worker.enabled=true", "--app.temporal.worker.role=CONTROL", "--app.temporal.worker.versioning-mode=$workerVersioningMode", "--app.temporal.worker.deployment-name=$controlDeploymentName", "--app.temporal.worker.build-id=$controlBuildId" `
        -WorkingDirectory $javaServiceDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $javaControlWorkerOut `
        -RedirectStandardError $javaControlWorkerErr `
        -PassThru
[System.IO.File]::WriteAllText(
    $javaControlWorkerPidFile,
    $javaControlWorkerProcess.Id.ToString()
)

$javaAgentWorkerProcess =
    Start-Process `
        -FilePath $javaExecutable `
        -ArgumentList "-Xms128m", "-Xmx1024m", "-XX:+ExitOnOutOfMemoryError", "-jar", $javaJar.FullName, "--spring.profiles.active=local,agent-worker", "--app.temporal.worker.enabled=true", "--app.temporal.worker.role=AGENT", "--app.temporal.worker.versioning-mode=$workerVersioningMode", "--app.temporal.worker.deployment-name=$agentDeploymentName", "--app.temporal.worker.build-id=$agentBuildId" `
        -WorkingDirectory $javaServiceDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $javaAgentWorkerOut `
        -RedirectStandardError $javaAgentWorkerErr `
        -PassThru
[System.IO.File]::WriteAllText(
    $javaAgentWorkerPidFile,
    $javaAgentWorkerProcess.Id.ToString()
)

$frontendProcess =
    Start-Process `
        -FilePath "cmd.exe" `
        -ArgumentList "/d", "/c", "pnpm dev" `
        -WorkingDirectory (Join-Path $projectRoot "apps/web") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendOut `
        -RedirectStandardError $frontendErr `
        -PassThru
[System.IO.File]::WriteAllText($frontendPidFile, $frontendProcess.Id.ToString())

# 业务位置：【开发与运维脚本】try：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
try {
    Wait-ForHttp `
        -Url "http://127.0.0.1:$($env:PYTHON_AGENT_PORT)/health" `
        -Name "Python Agent" `
        -Process $pythonAgentProcess
    Wait-ForJavaHealth -Process $javaProcess
    Wait-ForProcessLog `
        -Process $javaControlWorkerProcess `
        -LogFile $javaControlWorkerOut `
        -Pattern "Started DisputeApplication" `
        -Name "Java Control Worker"
    Wait-ForProcessLog `
        -Process $javaAgentWorkerProcess `
        -LogFile $javaAgentWorkerOut `
        -Pattern "Started DisputeApplication" `
        -Name "Java Agent Worker"
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
    Assert-ProjectListener `
        -Port 18000 `
        -Name "Python Agent" `
        -ExpectedCommandPattern "uvicorn|python-agent-service|app\.main:create_app"
}
# 业务位置：【开发与运维脚本】catch：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
catch {
    Write-Error $_
    Get-Content -LiteralPath $pythonAgentErr -Tail 60 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $javaErr -Tail 60 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $javaControlWorkerErr -Tail 60 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $javaAgentWorkerErr -Tail 60 -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $frontendErr -Tail 60 -ErrorAction SilentlyContinue
    Stop-TrackedProcess -PidFile $javaPidFile
    Stop-TrackedProcess -PidFile $javaControlWorkerPidFile
    Stop-TrackedProcess -PidFile $javaAgentWorkerPidFile
    Stop-TrackedProcess -PidFile $frontendPidFile
    Stop-TrackedProcess -PidFile $pythonAgentPidFile
    throw
}

Write-Output "Local development services are ready."
Write-Output "Frontend: http://127.0.0.1:5173"
Write-Output "Java API: http://127.0.0.1:8080"
Write-Output "Java Temporal workers: CONTROL and AGENT are running as separate local processes."
Write-Output "Python Agent: http://127.0.0.1:$($env:PYTHON_AGENT_PORT) (local uvicorn --reload)"
Write-Output "Stop them with: .\tools\dev\dev-local.ps1 -Stop"
