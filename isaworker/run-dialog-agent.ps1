<#
  Starts the dialog agent ON THE WINDOWS DESKTOP. isaworkercontainer runs inside Docker, which has no screen; when a worker
  needs a person ("Please enter your name:"), the container queues the dialog and this agent shows it as a real window on
  YOUR desktop and sends the answer back.

    .\run-dialog-agent.ps1                 # real Swing windows (leave this window open; Ctrl+C stops the agent)
    .\run-dialog-agent.ps1 -Ui console     # questions on the console instead (SSH session, scripted answers on stdin)

  The agent connects to the container (http://localhost:8181, the port it publishes on this machine only), so nothing on
  this computer listens for it. The shared secret is ISAWORKER_DIALOG_TOKEN from .env: the same value as in the container.
  It can be started before or after the container and the process; a waiting dialog appears as soon as it is running.
#>
param(
    [string]$Url = 'http://localhost:8181',
    [ValidateSet('swing', 'console')][string]$Ui = 'swing'
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Read-Env([string]$name) {
    $line = Get-Content "$here\.env" | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $null }
    ($line -replace "^$name=", '').Trim().Trim([char[]]@([char]34, [char]39))
}

# The portable JDK / Maven of this workspace (nothing is installed system-wide).
$tools = Join-Path (Split-Path $here -Parent) '.tools'
$jdk = Get-ChildItem "$tools\jdk-extract" -Directory | Select-Object -First 1
if (-not $jdk) { throw "No JDK under $tools\jdk-extract" }
$env:JAVA_HOME = $jdk.FullName
$maven = Get-ChildItem "$tools\maven-extract" -Directory | Select-Object -First 1
$env:Path = "$env:JAVA_HOME\bin;$($maven.FullName)\bin;$env:Path"

$jar = "$here\target\isaworker-1.0.0.jar"
if (-not (Test-Path $jar)) {
    Write-Host 'Building isaworker (first run) ...'
    & mvn -B -q -f "$here\pom.xml" -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
}

$env:ISAWORKER_DIALOG_TOKEN = Read-Env 'ISAWORKER_DIALOG_TOKEN'
if (-not $env:ISAWORKER_DIALOG_TOKEN) { throw 'ISAWORKER_DIALOG_TOKEN is not set in .env' }

Write-Host "Dialog agent: dialogs of $Url appear on this desktop ($Ui). Ctrl+C stops it."
& "$env:JAVA_HOME\bin\java.exe" "-Dloader.main=com.example.isaworker.agent.DialogAgentMain" -cp $jar `
    org.springframework.boot.loader.launch.PropertiesLauncher --url $Url --ui $Ui
