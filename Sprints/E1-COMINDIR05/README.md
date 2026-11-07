# COMINDIR05 - Eleição do líder de partição

Três projetos Java 11+, no pacote `raft04`, como indicado na ficha: `raft04-no`, `raft04-produtor` e `raft04-processador`. Cada uma das três partições tem três nós. Não é uma implementação completa do RAFT: não há commit por maioria, Pre-Vote, reenvio de entradas ou entrega causal.

## Compilar

No PowerShell, nesta pasta:

```powershell
foreach ($projeto in @('no', 'produtor', 'processador')) {
    $fontes = (Get-ChildItem "raft04-$projeto/src/raft04/*.java").FullName
    javac --release 11 -encoding UTF-8 -Xlint:all -d "build/$projeto" $fontes
    if ($LASTEXITCODE -ne 0) { throw "Falha na compilação de $projeto" }
}
```

## Executar

Todos os processos devem usar a mesma pasta de trabalho, na mesma máquina. Iniciar o Kubo com PubSub ativo (`ipfs daemon --enable-pubsub-experiment`). Versão usada nos ensaios: Kubo 0.39.0. A API padrão é `http://127.0.0.1:5001/api/v0`; pode ser alterada em todos os processos com `-Draft04.api=<url>`, antes de `-cp`.

Arrancar cada nó num terminal próprio; a ordem entre os nove nós é indiferente:

```text
java -cp build/no raft04.PartitionNode 0 1
java -cp build/no raft04.PartitionNode 0 2
java -cp build/no raft04.PartitionNode 0 3
java -cp build/no raft04.PartitionNode 1 1
java -cp build/no raft04.PartitionNode 1 2
java -cp build/no raft04.PartitionNode 1 3
java -cp build/no raft04.PartitionNode 2 1
java -cp build/no raft04.PartitionNode 2 2
java -cp build/no raft04.PartitionNode 2 3
```

Esperar por `LIDER` em cada partição. Depois arrancar, noutros terminais:

```text
java -cp build/processador raft04.AlarmProcessor 3
java -cp build/produtor raft04.Producer 3
```

O produtor aceita `<sensor> <temperatura>`, `rajada <quantidade> <intervalo-ms>`, `rajada-quente <quantidade> <intervalo-ms>` e `sair`. `rajada 300 10` envia 300 leituras com intervalo de 10 ms, escolhendo sensores S01-S10. `rajada-quente` concentra a carga em S01; não obriga as temperaturas a ultrapassar 28 graus.

Os portos TCP são `7000 + 3*k + i - 1`, isto é, 7000-7008. A propriedade opcional `raft04.portoBase` permite alterar a base de forma igual nos nove nós. Os tópicos são exatamente os da ficha: `raft04-in-p<k>`, `raft04-p<k>` e `raft04-eleicao-p<k>`.

O período é 2000 ms; os prazos de eleição são sorteados entre 6000 e 12000 ms; a verificação ocorre a cada 200 ms. Propriedades: `raft04.periodo`, `raft04.eleicaoMin`, `raft04.eleicaoMax`, `raft04.fator`.

## Falhar, reiniciar e isolar

Para terminar abruptamente um líder no Windows, identificar o seu PID, anotar a falha e terminá-lo:

```powershell
$noPid = 12345 # substituir pelo PID do nó escolhido
"p1;n2;$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" | Add-Content -Encoding UTF8 falhas.txt
Stop-Process -Id $noPid -Force
```

Os identificadores na linha devem corresponder ao processo escolhido. Reiniciar com o mesmo comando, na mesma pasta, conservando `estado-*` e `no-*`.

Para isolar n2 de p1, criar `isolar-p1-n2` nessa pasta; para reparar, remover esse ficheiro. Anotar os instantes. O isolamento afeta os lotes, as mensagens eleitorais e o TCP desse nó. As entradas IN continuam a chegar, pelo que um líder isolado ainda aceita leituras.

## Analisar

Após reparar as partições de rede, reiniciar os membros terminados e aguardar a convergência, parar os escritores antes de analisar:

```text
java -cp build/no raft04.RaftCheck 3 . falhas.txt
java -cp build/no raft04.CausalCheck 3 .
```

O argumento `falhas.txt` é opcional. Os resultados são escritos em `raft-check.txt`, `causal-check.txt` e `vista-lamport.txt`. Código de saída 2 indica violações; 1 indica erro de execução/análise.

`RaftCheck` relata termos sem líder como «voto dividido», acrescentando que também podem faltar votos por ausência de quórum. Não confundir um nó isolado sem maioria com três candidatos que dividiram os votos. A coincidência inclui a comparação até ao menor seq e também assinala comprimentos diferentes.

As perdas são inferidas por multiplicidade de `(sensor, temperatura)` entre as linhas do produtor e os registos finais. O protocolo IN não contém um identificador único da leitura; ocorrências idênticas não podem ser individualizadas com certeza. «Leituras no intervalo de indisponibilidade» também não é necessariamente o mesmo que «leituras perdidas»: o intervalo termina no primeiro lote do novo líder.

## Organização e sincronização

- `RaftState`: papel, termo, voto, prazo e contagem por conjunto de eleitores. Termo/voto são substituídos atomicamente antes de enviar mensagens dependentes deles. A operação não inclui `fsync`; não promete resistência a qualquer falha de energia.
- `PartitionNode`: subscrições IN/dados/eleição, temporizadores, persistência de registos e servidor TCP. A ordem é monitor do nó -> monitor de `RaftState` -> ficheiro/log. `RaftState` não chama métodos que adquiram o monitor do nó.
- `RecordFile`: histórico contíguo, append e substituição atómica. Na divergência recua por blocos de termo, encontra a fronteira e substitui a cauda pelo histórico do líder.
- A recuperação TCP ocorre fora do monitor do nó; antes de aplicar a resposta, volta a verificar termo, líder e isolamento. Uma resposta de uma autoridade já substituída não pode alterar o histórico.
- `Batch`, `VoteRequest`, `VoteReply`, `SensorRecord` e `Wire`: validação e serialização do protocolo.
- `HeartbeatMonitor`: termos observados por produtor/processador, suspeitas, novo líder e saltos de hb. O processador filtra duplicados por partição e pelo par `(seq, termo)`.
- Os relógios continuam a representar eventos de dados dos líderes, uma componente por partição. Eleições e heartbeats não fazem tick. Ao assumir liderança, recuperam-se seq/L/V do último registo local.

O evento `TRUNCADO` inclui `de`, `descartados` e `termos`. O campo adicional `registos` contém as linhas removidas codificadas em base64url, permitindo a `RaftCheck` procurar ocorrências do mesmo conteúdo com outro seq sem conservar os dados descartados no histórico ativo.

Os ensaios, medições e respetivos limites estão em [RELATORIO.md](RELATORIO.md).
