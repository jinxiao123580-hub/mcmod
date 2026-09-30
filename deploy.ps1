# deploy.ps1 - deploy maid_brain (AI maid brain addon) into ANY modpack's mods folder.
# ASCII only on purpose: PowerShell 5.1 reads no-BOM files as ANSI, non-ASCII would mojibake.
# Usage examples:
#   .\deploy.ps1 -ModsDir 'E:\MC\.minecraft\versions\SomePack\mods'
#   .\deploy.ps1 -ModsDir 'D:\packs\other\mods' -Jar 'D:\DSH\maid-ai\maid-brain\build\libs\maid_brain-forge-0.18.0.jar'
param(
    [Parameter(Mandatory = $true)]
    [string]$ModsDir,
    [string]$Jar = '',
    [string]$ProjectDir = 'D:\DSH\maid-ai\maid-brain',
    [string]$JavaHome = 'D:\DSH\tools\jdk17\jdk-17.0.20.1+1'
)

$ErrorActionPreference = 'Stop'

# --- 0. sanity checks -------------------------------------------------------
if (-not (Test-Path $ModsDir)) {
    Write-Host "FAIL: mods dir not found: $ModsDir" -ForegroundColor Red
    Write-Host "Pass the modpack instance's mods folder, e.g. -ModsDir 'E:\MC\.minecraft\versions\<pack>\mods'"
    exit 1
}
$tlm = Get-ChildItem $ModsDir -Filter '*.jar' | Where-Object { $_.Name -match 'touhoulittlemaid' }
if (-not $tlm) {
    Write-Host "FAIL: TouhouLittleMaid jar not found in $ModsDir" -ForegroundColor Red
    Write-Host "This addon extends TLM 1.5.x (Forge 1.20.1). Install TLM in that pack first."
    exit 1
}
Write-Host "[ok] TLM found: $($tlm.Name)"

# --- 1. build (or reuse prebuilt jar) ---------------------------------------
if (-not $Jar) {
    if (-not (Test-Path "$ProjectDir\gradlew.bat")) {
        Write-Host "FAIL: project not found at $ProjectDir" -ForegroundColor Red
        exit 1
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    Write-Host "[..] building maid-brain ..."
    Push-Location $ProjectDir
    $ErrorActionPreference = 'Continue' # javac deprecation notes go to stderr; don't abort on them
    try {
        & .\gradlew.bat build --no-daemon --quiet 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            & .\gradlew.bat build --no-daemon 2>&1 | Select-String -Pattern 'error|\.java:' | Select-Object -First 10
            Write-Host "FAIL: build failed" -ForegroundColor Red
            exit 1
        }
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    $built = Get-ChildItem "$ProjectDir\build\libs" -Filter 'maid_brain*.jar' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $built) {
        Write-Host "FAIL: no jar produced" -ForegroundColor Red
        exit 1
    }
    $Jar = $built.FullName
}
if (-not (Test-Path $Jar)) {
    Write-Host "FAIL: jar not found: $Jar" -ForegroundColor Red
    exit 1
}
$ver = [System.IO.Path]::GetFileName($Jar)
Write-Host "[ok] jar: $ver"

# --- 2. install: remove ALL old maid_brain jars, copy the new one -----------
$old = Get-ChildItem $ModsDir -Filter 'maid_brain*.jar'
if ($old) {
    $old | Remove-Item -Force
    Write-Host "[ok] removed old: $($old.Name -join ', ')"
}
Copy-Item $Jar $ModsDir
Write-Host "[ok] installed: $ver -> $ModsDir" -ForegroundColor Green

# --- 3. checklist ------------------------------------------------------------
Write-Host ''
Write-Host '=== in-game setup checklist (once per instance) ==='
Write-Host '1. Launch the pack, open a world, check the log for:'
Write-Host '   "Registered 11 maid_brain tools: ..." and "... prompt contexts (maid_brain)"'
Write-Host '2. Configure the maid brain: hold AI memory book item on a tamed maid,'
Write-Host '   create an OpenAI-type LLM site (e.g. DeepSeek URL + key), pick a model.'
Write-Host '3. (Optional) TTS site the same way (e.g. SiliconFlow CosyVoice2).'
Write-Host '4. Test: type to her in the chat bar; try /maidload on; try /maidchat <msg>.'
Write-Host ''
Write-Host '=== config files (auto-created at first use, per instance) ==='
Write-Host '   config\maid_brain\memory.json   long-term memory'
Write-Host '   config\maid_brain\tasks.json    her todo list'
Write-Host '   config\maid_brain\waypoints.json named places'
Write-Host '   config\maid_brain\relation.json affection'
Write-Host '   config\maid_brain\loaded.json   always-loaded maids'
Write-Host '   config\maid_brain\gifts.json    daily gift record (per game day)'
Write-Host '   config\maid_brain\modindex.json pack item index (rebuilt when mods change)'
