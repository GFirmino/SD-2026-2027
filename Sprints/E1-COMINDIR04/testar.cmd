@echo off
setlocal
set "COMINDIR04_ROOT=%~dp0"
set "COMINDIR04_SELF=%~f0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([scriptblock]::Create(((Get-Content -LiteralPath $env:COMINDIR04_SELF -Raw) -split '(?m)^:__POWERSHELL__\r?$',2)[1])) %*"
set "resultado=%errorlevel%"
if "%~1"=="" pause
exit /b %resultado%
:__POWERSHELL__
param([long]$Periodo=5000, [int]$Fator=3, [string]$Pasta='', [switch]$Controlar, [switch]$CompilarApenas)
$ErrorActionPreference='Stop'
$Root=$env:COMINDIR04_ROOT
# Resolver o executavel real evita controlar apenas o shim Oracle javapath.
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
$ErrorActionPreference = $savedPreference
if ($settings -notmatch 'java.home\s*=\s*([^\r\n]+)') { throw 'Nao foi possivel localizar o JDK' }
$JavaExe = Join-Path $Matches[1].Trim() 'bin/java.exe'
if (!(Test-Path -LiteralPath $JavaExe)) { throw "Java nao encontrado: $JavaExe" }
# O ambiente do Codex pode conter Path e PATH em simultâneo. O Start-Process do
# PowerShell trata os nomes sem distinguir maiúsculas e falha ao copiar ambos.
$processEnvironment = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
    $processEnvironment[[string]$entry.Key] = [string]$entry.Value
}
function Start-RedirectedProcess($file, $arguments, $workingDirectory, $stdout, $stderr) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $file
    $info.Arguments = $arguments
    $info.WorkingDirectory = $workingDirectory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    if (!$process.Start()) { throw "Nao foi possivel arrancar: $file" }
    $null = Register-ObjectEvent $process OutputDataReceived -MessageData $stdout -Action {
        if ($null -ne $EventArgs.Data) { [IO.File]::AppendAllText($event.MessageData, $EventArgs.Data + [Environment]::NewLine) }
    }
    $null = Register-ObjectEvent $process ErrorDataReceived -MessageData $stderr -Action {
        if ($null -ne $EventArgs.Data) { [IO.File]::AppendAllText($event.MessageData, $EventArgs.Data + [Environment]::NewLine) }
    }
    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()
    return $process
}

if ($Controlar) {
if (!$Pasta) { $Pasta = Read-Host 'Pasta de execucao indicada por demonstrar.cmd' }
$Pasta = (Resolve-Path -LiteralPath $Pasta).Path
if (Test-Path "$Pasta/sessao.json") {
    $session = Get-Content "$Pasta/sessao.json" -Raw | ConvertFrom-Json
    $Periodo = $session.periodo; $Fator = $session.fator; $Build = $session.build
}
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ProcessPause {
    [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr h);
    [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr h);
}
'@
while ($true) {
    $command = Read-Host 'Comando: falhar 0 | reiniciar 0 | pausar 2 | retomar 2 | sair'
    if ($command -eq 'sair') { break }
    if ($command -notmatch '^(falhar|reiniciar|pausar|retomar) ([012])$') { Write-Host 'Comando invalido'; continue }
    $action=$Matches[1]; $k=[int]$Matches[2]
    $pidFile=Join-Path $Pasta "lider$k.pid"
    $leader=$null
    if (Test-Path $pidFile) {
        $leaderId=[int](Get-Content $pidFile)
        $candidate=Get-CimInstance Win32_Process -Filter "ProcessId=$leaderId"
        if ($candidate -and $candidate.CommandLine.Replace('/','\').Contains((Join-Path $Build 'lider')) -and $candidate.CommandLine -like "*rep04.PartitionLeader $k 3*") { $leader=Get-Process -Id $leaderId }
    }
    if ($action -eq 'reiniciar') {
        if ($leader) { Write-Host 'Lider ainda existe'; continue }
        $stamp=Get-Date -Format 'HHmmssfff'
        $leader=Start-RedirectedProcess $JavaExe "-Drep04.periodo=$Periodo -Drep04.fator=$Fator -cp `"$Build/lider`" rep04.PartitionLeader $k 3" $Pasta "$Pasta/lider$k-$stamp.out.log" "$Pasta/lider$k-$stamp.err.log"
        Set-Content $pidFile $leader.Id
    } elseif (!$leader) { Write-Host 'Lider nao esta em execucao' }
    elseif ($action -eq 'falhar') {
        $instant=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $leader.Kill(); $leader.WaitForExit()
        Add-Content -Encoding UTF8 "$Pasta/falhas.txt" "p$k;$instant"
    } elseif ($action -eq 'pausar') {
        if ([ProcessPause]::NtSuspendProcess($leader.Handle) -ne 0) { throw 'Falha ao suspender' }
    } else {
        if ([ProcessPause]::NtResumeProcess($leader.Handle) -ne 0) { throw 'Falha ao retomar' }
    }
}

return
}
if ($Periodo -le 0 -or $Fator -le 0) { throw 'Periodo e fator devem ser positivos' }
if (!$Pasta) { $Pasta=Join-Path $env:TEMP ('COMINDIR04/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
$Pasta=[IO.Path]::GetFullPath($Pasta)
$Build=Join-Path $Pasta 'build'
New-Item -ItemType Directory -Force $Build | Out-Null
foreach ($papel in @('produtor','lider','replica','processador')) {
    $fontes=(Get-ChildItem "$Root/rep04-$papel/src/rep04/*.java").FullName
    New-Item -ItemType Directory -Force "$Build/$papel" | Out-Null
    & javac --release 11 -encoding UTF-8 -d "$Build/$papel" $fontes
    if ($LASTEXITCODE -ne 0) { throw "Compilacao falhou: $papel" }
}
Write-Host 'COMPILACAO: OK'
$testSource=@'
package rep04;

import java.util.*;

/** Casos adversos do protocolo e do detetor, sem frameworks. */
public final class CoreTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Mensagem invalida foi aceite");
    }
    public static void main(String[] args) throws Exception {
        SensorRecord r=SensorRecord.now(1,"S01",21,1,VectorClock.parse("1,0,0",3));
        ArrayList<SensorRecord> pending=new ArrayList<>(); pending.add(r);
        Batch b=new Batch("127.0.0.1",7000,100,1,1,pending); pending.clear();
        check(Batch.decode(b.encode(),3).records.size()==1,"Snapshot depende da lista mutavel");
        check(Batch.header(b.encode()).count==1,"Leitura do cabecalho");
        check(Batch.decode(new Batch("127.0.0.1",7000,100,2,1,List.of()).encode(),3).records.isEmpty(),"Lote vazio");
        rejects(()->Batch.decode("LOTE;127.0.0.1;7000;100;1;1;2\n"+r.toLine(),3));
        rejects(()->Batch.decode("LOTE;127.0.0.1;7000;100;1;0;1\n"+r.toLine(),3));
        rejects(()->Batch.decode("LOTE;127.0.0.1;7000;100;1;1;2\n"+r.toLine()+"\n"+r.toLine(),3));
        rejects(()->Batch.header("LOTE;127.0.0.1;99999;100;1;0;0"));
        List<String> events=new ArrayList<>();
        HeartbeatMonitor m=new HeartbeatMonitor(0,5,1,events::add);
        check(m.state()==HeartbeatMonitor.State.DESCONHECIDO,"Estado inicial");
        m.onHeartbeat(100,1); Thread.sleep(15); m.check(); m.check();
        check(events.stream().filter(x->x.startsWith("SUSPEITA")).count()==1,"Suspeita repetida");
        m.onHeartbeat(99,99); m.onHeartbeat(100,1);
        check(m.state()==HeartbeatMonitor.State.SUSPEITO,"Lote antigo/duplicado renovou timeout");
        m.onHeartbeat(100,3);
        check(m.state()==HeartbeatMonitor.State.ATIVO,"Nao retomou");
        check(events.stream().anyMatch(x->x.startsWith("HB_PERDIDO")),"Salto nao detetado");
        check(events.stream().anyMatch(x->x.startsWith("RETOMA")),"Retoma nao registada");
        m.onHeartbeat(101,1);
        check(events.stream().anyMatch(x->x.startsWith("REINICIO")),"Reinicio nao registado");
        System.out.println("CORE TESTS: OK");
    }
}

'@
Set-Content -Encoding UTF8 "$Pasta/CoreTest.java" $testSource
# Java nao aceita BOM na fonte: escrever explicitamente UTF-8 sem BOM.
[IO.File]::WriteAllText("$Pasta/CoreTest.java", $testSource, [Text.UTF8Encoding]::new($false))
& javac --release 11 -encoding UTF-8 -cp "$Build/replica" -d "$Build/tests" "$Pasta/CoreTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Compilacao dos testes falhou' }
& $JavaExe -cp "$Build/tests;$Build/replica" rep04.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Testes do protocolo falharam' }
if ($CompilarApenas) { Write-Host "Resultados: $Pasta"; return }
@{periodo=$Periodo; fator=$Fator; build=$Build} | ConvertTo-Json | Set-Content "$Pasta/sessao.json"
$processos = [Collections.Generic.List[System.Diagnostics.Process]]::new()
$processosPorNome = @{}
$daemon = $null
$ready = $false
function Iniciar($nome, $papel, $classe, $argumentos) {
    $cp = Join-Path $Build $papel
    $p = Start-RedirectedProcess $JavaExe "-Drep04.periodo=$Periodo -Drep04.fator=$Fator -cp `"$cp`" rep04.$classe $argumentos" $Pasta "$Pasta/$nome.out.log" "$Pasta/$nome.err.log"
    $processos.Add($p)
    $processosPorNome[$nome] = $p
    return $p
}
function Esperar($nome, $quantidade) {
    $limite = (Get-Date).AddSeconds(5)
    do {
        $linhas = @(Get-Content "$Pasta/$nome.out.log" -ErrorAction SilentlyContinue | Select-String 'SUBSCRITO:')
        if ($linhas.Count -ge $quantidade) { return }
        if ($processosPorNome[$nome].HasExited) {
            throw "Processo terminou durante o arranque: $nome. Consulta os logs em $Pasta"
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $limite)
    # No Windows PowerShell 5, os eventos que copiam stdout podem ficar em fila
    # enquanto este script está ocupado. Um processo local ainda ativo após a
    # janela de arranque pode prosseguir; o Kubo confirma depois a subscrição.
    if (!$processosPorNome[$nome].HasExited) { return }
    throw "Subscricao nao ficou pronta: $nome. Consulta os logs em $Pasta"
}
try {
    try { $null = Invoke-RestMethod -Method Post -UserAgent 'COMINDIR04' -ContentType 'application/octet-stream' -Uri 'http://127.0.0.1:5001/api/v0/version' -TimeoutSec 2 }
    catch {
        $ipfsExe = (Get-Command ipfs -ErrorAction Stop).Source
        $daemon = Start-RedirectedProcess $ipfsExe 'daemon' $Pasta "$Pasta/kubo.out.log" "$Pasta/kubo.err.log"
        $pronto = $false
        for ($i=0; $i -lt 40; $i++) {
            Start-Sleep -Milliseconds 250
            try { $null = Invoke-RestMethod -Method Post -UserAgent 'COMINDIR04' -ContentType 'application/octet-stream' -Uri 'http://127.0.0.1:5001/api/v0/version' -TimeoutSec 1; $pronto=$true; break } catch { }
        }
        if (!$pronto) { throw 'Kubo nao arrancou. Consulta kubo.err.log.' }
    }
    $null = Invoke-RestMethod -Method Post -UserAgent 'COMINDIR04' -ContentType 'application/octet-stream' -Uri 'http://127.0.0.1:5001/api/v0/pubsub/ls' -TimeoutSec 3
    $null = Iniciar 'replica1' 'replica' 'PartitionReplica' '1 0,1'; Esperar 'replica1' 2
    $null = Iniciar 'replica2' 'replica' 'PartitionReplica' '2 1,2'; Esperar 'replica2' 2
    $null = Iniciar 'replica3' 'replica' 'PartitionReplica' '3 2,0'; Esperar 'replica3' 2
    for ($k=0; $k -lt 3; $k++) {
        $p = Iniciar "lider$k" 'lider' 'PartitionLeader' "$k 3"
        Set-Content "$Pasta/lider$k.pid" $p.Id
        Esperar "lider$k" 1
    }
    $null = Iniciar 'processador' 'processador' 'AlarmProcessor' '3'; Esperar 'processador' 3
    $ready = $true
    Write-Host "Sistema pronto. Ficheiros e logs: $Pasta"
    Write-Host 'Para controlar lideres: testar.cmd -Controlar -Pasta <pasta indicada>'
    Push-Location $Pasta
    try {
        & $JavaExe "-Drep04.periodo=$Periodo" "-Drep04.fator=$Fator" -cp "$Build/produtor" rep04.Producer 3
        Write-Host 'A aguardar os ultimos lotes e alarmes...'
        Start-Sleep -Milliseconds ([int](2*$Periodo+1500))
    } finally { Pop-Location }
} finally {
    $verificationErrors = [Collections.Generic.List[string]]::new()
    foreach ($pidFile in Get-ChildItem "$Pasta/lider*.pid" -ErrorAction SilentlyContinue) {
        $leaderId = [int](Get-Content $pidFile.FullName)
        $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$leaderId" -ErrorAction SilentlyContinue
        if ($candidate -and $candidate.CommandLine.Replace('/','\').Contains($Build.Replace('/','\')) -and $candidate.CommandLine -like '*rep04.PartitionLeader*') {
            Stop-Process -Id $leaderId -ErrorAction SilentlyContinue
        }
    }
    foreach ($p in $processos) { if (!$p.HasExited) { $p.Kill(); $p.WaitForExit() } }
    if ($ready) {
    $checkArgs = @('3', $Pasta)
    if (Test-Path "$Pasta/falhas.txt") { $checkArgs += "$Pasta/falhas.txt" }
    & $JavaExe -cp "$Build/replica" rep04.HeartbeatCheck @checkArgs
    if ($LASTEXITCODE -ne 0) { $verificationErrors.Add('HeartbeatCheck') }
    & $JavaExe -cp "$Build/replica" rep04.CausalCheck 3 $Pasta | Tee-Object "$Pasta/causal-check.txt"
    if ($LASTEXITCODE -ne 0) { $verificationErrors.Add('CausalCheck') }
    & $JavaExe -cp "$Build/replica" rep04.PartitionCheck 3 $Pasta | Tee-Object "$Pasta/partition-check.txt"
    if ($LASTEXITCODE -ne 0) { $verificationErrors.Add('PartitionCheck') }
    }
    if ($daemon -and !$daemon.HasExited) { & ipfs shutdown | Out-Null }
    Write-Host "Resultados: $Pasta"
    if ($verificationErrors.Count -gt 0) {
        throw "Verificadores com erro: $($verificationErrors -join ', ')"
    }
}
