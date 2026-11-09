<#
  Deploys a BPMN model to a tenant's Fluxnova engine, MANUALLY. isaworker itself never deploys anything: the models live in
  the folder bpmn\ and you decide when and where each one is deployed.

    .\deploy-bpmn.ps1                                        # bpmn\worker-details-process.bpmn  ->  tenant-x
    .\deploy-bpmn.ps1 -File demo-process.bpmn -Tenant tenant-x
    .\deploy-bpmn.ps1 -Start                                 # deploy, then start one instance: the dialogs pop up on the
                                                             # desktop where run-dialog-agent.ps1 runs

  The REST credentials come from .env (FLUXNOVA_REST_USER / FLUXNOVA_REST_PASSWORD). Deploying the same, unchanged model
  again does not create a new version (the engine filters duplicates).
#>
param(
    # A file name inside bpmn\, or a path.
    [string]$File = 'worker-details-process.bpmn',
    [string]$Tenant = 'tenant-x',
    # The Fluxnova container publishes its REST API here.
    [string]$Url = 'http://localhost:8080',
    # Also start one instance of every process in the model.
    [switch]$Start
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Read-Env([string]$name) {
    $line = Get-Content "$here\.env" | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $null }
    ($line -replace "^$name=", '').Trim().Trim([char[]]@([char]34, [char]39))
}

$path = if (Test-Path $File) { (Resolve-Path $File).Path } else { Join-Path "$here\bpmn" $File }
if (-not (Test-Path $path)) { throw "Model not found: $File (looked in $here\bpmn)" }
$user = Read-Env 'FLUXNOVA_REST_USER'; if (-not $user) { $user = 'admin' }
$password = Read-Env 'FLUXNOVA_REST_PASSWORD'
$engine = "$Url/engine-rest/engine/$Tenant"
$name = Split-Path -Leaf $path

Write-Host "Deploying $name to $engine ..."
$answer = (& curl.exe -s -m 60 -u "${user}:$password" -w "`n%{http_code}" `
    -F "deployment-name=$name" -F "enable-duplicate-filtering=true" -F "deployment-source=manual" `
    -F "$name=@$path" "$engine/deployment/create") -join "`n"
$code = [int]($answer -split "`n")[-1]
$body = ($answer -split "`n" | Select-Object -SkipLast 1) -join "`n"
if ($code -ne 200) { throw "The engine answered HTTP ${code}: $body" }
$deployment = $body | ConvertFrom-Json
$definitions = @()
if ($deployment.deployedProcessDefinitions) { $definitions = @($deployment.deployedProcessDefinitions.PSObject.Properties.Value) }
if ($definitions.Count -eq 0) {
    Write-Host "Deployment $($deployment.id): unchanged, the engine already has exactly this model."
    $found = (& curl.exe -s -m 30 -u "${user}:$password" "$engine/process-definition?deploymentId=$($deployment.id)") -join '' | ConvertFrom-Json
    $definitions = @($found)
} else {
    Write-Host "Deployment $($deployment.id): $(($definitions | ForEach-Object { "$($_.key) v$($_.version)" }) -join ', ')"
}

if ($Start) {
    $empty = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($empty, '{}')
    try {
        foreach ($definition in $definitions) {
            $started = (& curl.exe -s -m 60 -u "${user}:$password" -X POST -H "Content-Type: application/json" -d "@$empty" `
                "$engine/process-definition/key/$($definition.key)/start") -join '' | ConvertFrom-Json
            if (-not $started.id) { throw "Could not start $($definition.key): $($started | ConvertTo-Json -Compress)" }
            Write-Host "Started $($definition.key): process instance $($started.id)"
        }
    } finally { Remove-Item $empty -Force -ErrorAction SilentlyContinue }
}
