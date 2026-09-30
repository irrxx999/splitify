# Сборка Splitify. Сам находит Maven (PATH или встроенный в IntelliJ IDEA).
#   .\scripts\build.ps1            — clean package (собирает target\splitify.jar)
#   .\scripts\build.ps1 -Compile   — только компиляция; безопасно, пока бот запущен
param([switch]$Compile)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Find-Mvn {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $idea = Get-ChildItem 'C:\Program Files\JetBrains\*\plugins\maven\lib\maven3\bin\mvn.cmd' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($idea) { return $idea.FullName }
    throw 'Maven не найден: добавь его в PATH или установи IntelliJ IDEA.'
}

if ($Compile) {
    $goals = @('compile')
} else {
    # Запущенный бот держит jar открытым — clean не сможет его удалить.
    $running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match 'splitify\.jar' }
    if ($running) {
        throw 'Бот запущен и держит target\splitify.jar. Останови его (Ctrl+C в его окне) или используй -Compile.'
    }
    $goals = @('clean', 'package')
}

& (Find-Mvn) -q -B @goals
exit $LASTEXITCODE
