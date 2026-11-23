# COMINDIR03

Quatro projetos Java, com as classes no pacote `rep03`. Requer JDK 11 ou superior e Kubo com PubSub ativo. Versão usada nos ensaios: Kubo 0.39.0. Os resultados e as respostas da ficha estão em [RELATORIO.md](RELATORIO.md).

## Compilar

No PowerShell, a partir desta pasta:

```powershell
foreach ($projeto in @('produtor', 'lider', 'replica', 'processador')) {
    $fontes = (Get-ChildItem -Path "rep03-$projeto/src/rep03/*.java").FullName
    javac --release 11 -encoding UTF-8 -d "build/$projeto" $fontes
}
```

## Executar

Arrancar o daemon IPFS:

```text
ipfs daemon --enable-pubsub-experiment
```

Executar cada comando num terminal próprio, pela ordem indicada, mantendo esta pasta como diretório de trabalho:

```text
java -cp build/replica rep03.PartitionReplica 1 0,1
java -cp build/replica rep03.PartitionReplica 2 1,2
java -cp build/replica rep03.PartitionReplica 3 2,0

java -cp build/lider rep03.PartitionLeader 0 3
java -cp build/lider rep03.PartitionLeader 1 3
java -cp build/lider rep03.PartitionLeader 2 3

java -cp build/processador rep03.AlarmProcessor 3
java -cp build/produtor rep03.Producer 3
```

Aguardar pelas mensagens `SUBSCRITO` antes de iniciar o produtor. A API padrão é `http://127.0.0.1:5001/api/v0`. Os líderes usam os portos TCP 7000, 7001 e 7002. Para máquinas distintas, indicar ao líder o endereço que as réplicas conseguem alcançar: `PartitionLeader <k> <N> [endereço] [api]`.

O produtor aceita `<sensor> <temperatura>`, `rajada <n> <s>`, `rajada-quente <n> <s>` e `sair`. Por exemplo, `S02 29.3` publica uma leitura em p1 e origina um alarme em p2.

Depois de parar os escritores, analisar os ficheiros:

```text
java -cp build/replica rep03.CausalCheck 3 .
java -cp build/replica rep03.PartitionCheck 3 .
```

`CausalCheck` escreve `vista-lamport.txt`, com linhas `p<k>;seq;sensor;temperatura;instante;L;V` ordenadas por (L,k). Essa vista não representa a ordem de chegada às réplicas.

Para reiniciar um líder, conservar o respetivo `lider-p<k>.txt` e executar o mesmo comando na mesma pasta. Para simular perdas numa réplica, acrescentar `"-Ddescartar=1"` ou `"-Dperda=0.2"` antes de `-cp`; estas propriedades atuam apenas no PubSub.
