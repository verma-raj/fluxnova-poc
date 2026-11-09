# Checks that a tenant's engine is really connected to ITS OWN database. Usage:  .\check-tenant.ps1 -Tenant tenant-x
# Needs the local containers (fluxnova-application on :8080, fluxnova-postgres). The database password below is the local dev one.
param(
    [string]$Tenant = "tenant-x",                        # the tenant id from tenants.yml
    [string]$Base   = "http://localhost:8080"
)
$pg = "docker exec -e PGPASSWORD=secure_password fluxnova-postgres psql -U postgres_admin"

# 1. What the application says
$all = Invoke-RestMethod "$Base/api/tenants"
$engine = $all.active | Where-Object tenantId -eq $Tenant
if (-not $engine) {
    "RESULT: '$Tenant' has NO running engine."
    $failure = $all.failed | Where-Object tenantId -eq $Tenant
    if ($failure) { "It failed to start: $($failure.lastError)  (attempt $($failure.attempts), next retry $($failure.nextRetryAt))" }
    else { "It is not in tenants.yml, or the app has not picked it up yet (it checks every 15 s)." }
    return
}
"1. Application : state=$($engine.state)  database=$($engine.dbName)"
"                 url=$($engine.jdbcUrl)"
"                 pool active/idle/max = $($engine.pool.active)/$($engine.pool.idle)/$($engine.pool.maximum)"

# 2. The engine really runs queries against its database right now
$live = Invoke-RestMethod "$Base/api/tenants/$Tenant"
"2. Live query  : deployments=$($live.deployments)  processDefinitions=$($live.processDefinitions)  runningInstances=$($live.runningProcessInstances)  jobs=$($live.jobs)"

# 3. What PostgreSQL says: which database holds the connections labelled with this tenant
$rows = Invoke-Expression "$pg -d postgres -At -F '|' -c `"SELECT datname, count(*) FROM pg_stat_activity WHERE application_name = 'fluxnova:$Tenant' GROUP BY 1`""
$connected = @($rows | Where-Object { $_ } | ForEach-Object { $p = $_ -split '\|'; [pscustomobject]@{ Database = $p[0]; Connections = [int]$p[1] } })
"3. PostgreSQL  : " + $(if ($connected) { ($connected | ForEach-Object { "$($_.Connections) connection(s) to '$($_.Database)'" }) -join ", " } else { "NO connections labelled fluxnova:$Tenant" })

# 4. The engine's own tables are in that database
$tables = Invoke-Expression "$pg -d $($engine.dbName) -At -c `"SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'act\_%'`""
"4. Schema      : $tables engine tables (act_*) in '$($engine.dbName)'"

# Verdict
$onlyItsOwn = $connected.Count -gt 0 -and -not ($connected | Where-Object { $_.Database -ne $engine.dbName })
if ($engine.state -eq "RUNNING" -and $onlyItsOwn -and [int]$tables -gt 0) {
    "RESULT: OK - engine '$Tenant' is connected to its own database '$($engine.dbName)', and to no other."
} else {
    "RESULT: PROBLEM - the checks above do not agree. Look at the application log: docker logs fluxnova-application"
}

