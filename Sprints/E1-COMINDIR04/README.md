# COMINDIR04

Quatro projetos Java, como na COMINDIR03, com as classes no pacote `rep04`. Requer JDK 11 ou superior e Kubo com PubSub ativo. Código compilado com `--release 11`.

## Executar e testar

1. Faz duplo clique em **iniciar-kubo.cmd** para iniciar o Kubo e deixa a janela aberta. O Kubo já está configurado neste computador.
2. Faz duplo clique em **testar.cmd**. Compila os quatro projetos, executa os testes do protocolo e arranca réplicas, líderes, processador e produtor. Também inicia o Kubo automaticamente se ainda não estiver ativo.
3. Espera pelas mensagens `SUBSCRITO`. Envia `S02 29.3`, `S01 21.0` ou `rajada 300 10`.
4. Escreve `sair`: aguarda os últimos lotes, termina os processos da sessão e executa HeartbeatCheck, CausalCheck e PartitionCheck.

Os binários, logs e resultados ficam em **`%TEMP%\COMINDIR04\<data-hora>`**, indicada no ecrã. Não são criadas pastas auxiliares nesta ficha. Não abras duas sessões ao mesmo tempo: usam os mesmos tópicos e portos.

Num terminal CMD aberto nesta pasta:

```bat
testar.cmd -CompilarApenas
testar.cmd -Periodo 1000
testar.cmd -Periodo 10000
testar.cmd -Controlar -Pasta "C:\caminho\da\sessao"
```

O modo `-CompilarApenas` compila e testa Batch e HeartbeatMonitor sem precisar de Kubo. O modo `-Controlar` aceita `falhar 0`, `reiniciar 0`, `pausar 2`, `retomar 2` e `sair`. Lê os parâmetros da sessão e anota as falhas em `falhas.txt`.

O `.cmd` contém todo o apoio necessário; usa o PowerShell incluído no Windows, sem ficheiros `.ps1` separados ou dependência de Python. Mantém um daemon Kubo que já estava ativo e termina apenas o que ele próprio iniciou.

`rajada 300 10` significa 300 leituras com espera de 10 ms entre envios. Na COMINDIR03 o segundo argumento tinha outro significado; foi adaptado à ficha atual.

## Organização

- `rep04-produtor`: Producer.
- `rep04-lider`: PartitionLeader.
- `rep04-replica`: PartitionReplica e verificadores.
- `rep04-processador`: AlarmProcessor.
- `RELATORIO.md`: resultados dos ensaios e limites da validação.
- [`COMINDIR04-diagrama-classes.html`](COMINDIR04-diagrama-classes.html): diagrama navegável, separado em visão geral, aplicações, heartbeat/lotes, verificadores e ligações UML entre as classes.

## Como estudar e o que este guia cobre

Este README explica o código atual, a evolução das quatro tarefas, os métodos, as decisões de sincronização e a leitura dos resultados. Estuda primeiro a comparação entre versões, depois segue uma leitura e uma falha, e só então consulta o inventário de métodos.

Para apresentar, precisas de conseguir **explicar sem ler e mostrar no código**. Decorar o README não garante uma apresentação sem dificuldades. A parte experimental da ficha continua incompleta: faltam a matriz completa dos períodos, as três repetições de falha de p1 e as mutações deliberadas. O `RELATORIO.md` distingue os testes realizados desses ensaios pendentes.

## Evolução: COMINDIR01 → COMINDIR02 → COMINDIR03 → COMINDIR04

Esta comparação descreve as implementações que existem neste repositório, não uma versão hipotética das fichas.

| Aspeto | COMINDIR01 | COMINDIR02 | COMINDIR03 | COMINDIR04 |
|---|---|---|---|---|
| Problema principal | Difundir e replicar leituras, observando ordem e perdas | Dividir os dados em partições e recuperar lacunas | Representar dependências entre eventos de líderes diferentes | Distinguir silêncio de ausência de trabalho através de heartbeats e agrupar envios |
| Papéis | Líder recebe input e réplicas guardam cópias | Produtor separado, líderes por partição e réplicas | Acrescenta processador de alarmes | Mantém os quatro papéis e acrescenta monitores |
| Transporte | Duas variantes: multicast UDP e PubSub IPFS | PubSub para entradas/registos, TCP para recuperação | Conserva PubSub e TCP | Conserva transporte, substitui REG por LOTE no tópico de dados |
| Particionamento | Uma sequência do líder, sem divisão por sensor em N partições | `floorMod(hashCode(sensor), N)` | Mesma regra | Mesma regra |
| Replicação | Réplicas acompanham o fluxo único | Cada réplica acompanha partições atribuídas | Preserva atribuição e cópias | Preserva atribuição e cópias |
| Carimbo do registo | seq, sensor, temperatura e instante | Mesmos campos, seq local à partição | Acrescenta L e V | Preserva os mesmos campos e os mesmos eventos lógicos |
| Publicação de dados | Um registo por publicação/envio | Um REG por registo | Um REG por registo | Um LOTE por período, com zero ou mais registos |
| Lacunas | Retém registos à frente da sequência; não há recuperação TCP nesta base | Recuperação por TCP quando os retidos ultrapassam o limite | Conserva a regra e transporta os carimbos | Acrescenta recuperação quando last é inferior a ultimoSeq, mesmo num lote vazio |
| Silêncio do líder | Não identifica uma falha | Não identifica uma falha | Relógios lógicos também não identificam a falha | Monitor produz SUSPEITO após timeout |
| Reinício | Restaura seq do ficheiro | Restaura seq por partição | Restaura seq, L e V | Restaura seq/L/V e muda inc; hb recomeça em 1 |
| Análise | Contadores/resumos de rajadas | PartitionCheck | Acrescenta CausalCheck | Acrescenta HeartbeatCheck |

### O que a COMINDIR01 ensinou e o significado de burstTimer

Em `rep01-lider`, `MulticastLeader` envia datagramas para um grupo multicast; `PubSubLeader` publica no tópico `rep01-temperaturas`. O líder recebe diretamente os comandos, atribui seq e escreve `lider.txt`. A réplica aplica o próximo seq, ignora duplicados e retém registos que chegaram à frente de uma lacuna.

Na réplica PubSub da COMINDIR01, **`burstTimer` existe**. `scheduleBurstSummary()` cancela a tarefa de resumo anterior e agenda outra depois de 1500 ms sem novas leituras SIM. Serve para considerar terminada uma rajada e apresentar as medições. **Não é um detetor de falhas do líder**: a ausência de dados pode significar simplesmente que a rajada acabou.

Na COMINDIR04, `Producer.main` gera a rajada com um ciclo e `Thread.sleep`; `PartitionLeader.start` agenda o envio de lotes; `MonitorLog.schedule` agenda a verificação de suspeitas. São três trabalhos diferentes, com temporizações diferentes. Esta distinção responde diretamente ao feedback sobre reconhecer o código existente.

### O que a COMINDIR02 acrescentou

O produtor deixa de ser o próprio líder. Calcula a partição do sensor e publica IN no tópico de entrada correspondente. Cada líder tem o seu seq e o seu ficheiro. O REG anuncia endereço e porto para que a réplica consiga pedir registos em falta diretamente por TCP.

O `TreeMap` de retidos permite guardar registos que chegaram adiantados e aplicá-los assim que a sequência anterior fica completa. O limite de retidos desencadeia recuperação. Se nunca chegar um registo posterior, a perda do último registo pode não gerar esse sinal. A COMINDIR04 trata precisamente esse caso com ultimoSeq.

### O que a COMINDIR03 acrescentou

`VectorClock` e o campo Lamport permitem representar e analisar causalidade. O processador observa leituras e pode criar uma dependência entre uma leitura numa partição e um alarme noutra. Os líderes fazem os incrementos; o processador acumula conhecimento; as réplicas preservam os carimbos.

Se A provoca B, espera-se L(A) < L(B). O contrário não é suficiente para provar causalidade. Nos vetores, A é anterior a B quando todas as componentes de A são menores ou iguais às de B e pelo menos uma é menor. Se há uma componente maior em cada lado, os eventos são concorrentes. Ordenar por `(L,k)` cria uma vista total, mas não inventa uma relação de causa.

### O que a COMINDIR04 acrescentou sem substituir o anterior

`Batch` representa o lote; `HeartbeatMonitor` gere as suspeitas; `MonitorLog` regista eventos e agenda verificações; `HeartbeatCheck` analisa esses ficheiros. O líder mantém uma lista pendente e um temporizador de publicação. As réplicas usam o progresso anunciado no cabeçalho, e produtor/processador passam a acompanhar a saúde dos destinos.

Os relógios, o particionamento, a persistência e o serviço TCP continuam a servir os mesmos problemas. Um heartbeat não é um novo evento de dados. A tarefa também não passa a ter eleição de líder, confirmação das leituras ou entrega causal.

## 1. O que mudou desde a COMINDIR03?

Antes, um líder publicava um `REG` por leitura. Se não houvesse leituras, não publicava nada. Para uma réplica, um minuto de silêncio podia significar tanto ausência de trabalho como falha do líder.

Agora cada líder publica um `LOTE` imediatamente no arranque e depois a cada cinco segundos. Esse lote contém todos os registos que ficaram pendentes desde o envio anterior. Quando não há registos, publica um lote vazio. A chegada periódica é o **heartbeat**, um sinal de vida.

É como um serviço que envia um relatório de cinco em cinco segundos: um relatório vazio continua a dizer que o serviço está a responder. A ausência de relatórios levanta uma suspeita, mas também pode resultar de um atraso na comunicação.

## 2. Quem faz o quê?

| Processo | Responsabilidade |
|---|---|
| Produtor | Lê comandos e publica entradas; acompanha os líderes e avisa se o destino está suspeito |
| Líder p0, p1 ou p2 | Numera, atualiza relógios, persiste entradas e publica lotes da sua partição |
| Réplica 1 | Guarda p0 e p1 |
| Réplica 2 | Guarda p1 e p2 |
| Réplica 3 | Guarda p2 e p0 |
| Processador | Observa registos e publica alarmes para temperaturas de pelo menos 28 graus |
| Kubo | Fornece o transporte PubSub usado pelas aplicações |

**Particionar** é dividir os registos por líderes. **Replicar** é copiar os registos de uma partição. A função continua a ser `Math.floorMod(sensor.hashCode(), N)`: depende do nome do sensor e de N, não da temperatura nem da quantidade de réplicas.

Com N=3, S01 pertence a p0, S02 a p1 e S03 a p2. ALARM-S02 pertence a p2. Uma réplica guarda duas sequências independentes; p0#1 e p1#1 são registos diferentes.

## 3. Programa, processo e thread

`PartitionLeader.java` é código de um programa. Arrancá-lo três vezes produz três processos, com memórias distintas. Dentro de cada processo podem existir várias threads a aceder aos mesmos objetos.

| Programa | Threads relevantes | Estado partilhado |
|---|---|---|
| Líder | Receção de IN, temporizador de lotes e servidor TCP | seq, L, V, pendentes e ficheiro |
| Réplica | Uma receção por partição e verificações periódicas | Monitor de cada partição; log comum do participante |
| Processador | Três subscrições e verificação dos monitores | Conhecimento L/V e conjuntos de seq já vistos |
| Produtor | Leitura/envio de comandos, três subscrições e verificação | Monitores, sem relógios lógicos acumulados |

`synchronized` coordena threads que usam o mesmo monitor **na mesma JVM**. Não bloqueia outros líderes ou outros computadores. Cada `HeartbeatMonitor` protege o seu estado. `MonitorLog` serializa as escritas das threads do mesmo participante para não misturar linhas.

## 4. Os canais e os portos

`rep04-in-p1` recebe entradas destinadas ao líder p1. `rep04-p1` transporta lotes publicados por esse líder. A API HTTP do Kubo fica em `127.0.0.1:5001`; os servidores TCP de recuperação usam 7000, 7001 e 7002.

O produtor não escolhe um porto TCP de recuperação para cada leitura. Publica no tópico calculado. Já a réplica abre uma ligação TCP ao endereço e porto anunciados no cabeçalho do lote. O sistema operativo atribui um porto local efémero ao cliente quando a ligação é estabelecida.

PubSub não fornece um histórico persistente aos subscritores que chegam tarde. A recuperação dos registos vem dos ficheiros do líder, através do protocolo TCP `PEDIDO de ate`, linhas de registos e `FIM`.

## 5. Percurso de S02 29.3

Partindo de históricos vazios:

1. O produtor calcula p1 e publica `IN;S02;29.3;0;0,0,0`.
2. O líder p1 cria seq=1, L=1 e V=(0,1,0), escreve o ficheiro e acrescenta o registo a `pendentes`.
3. No próximo heartbeat, esse registo sai num `LOTE`.
4. As réplicas de p1 aplicam a cópia, preservando os carimbos.
5. O processador incorpora o conhecimento e publica uma entrada para ALARM-S02, que pertence a p2.
6. O líder p2 cria o alarme com L=2 e V=(0,1,1), guardando-o até ao próximo lote.
7. As réplicas de p2 aplicam o alarme.

Os valores pressupõem que não houve outros eventos. A espera nos dois líderes pode acrescentar até cerca de 2Δ, ou 10 s com Δ=5 s. A rede, o processamento e eventuais atrasos somam-se a isso; não é um limite absoluto num sistema assíncrono.

## 6. Ler um lote

```text
LOTE;127.0.0.1;7000;1793550000000;4;2;2
1;S01;21.0;2026-11-01T17:00:01;1;1,0,0
2;S04;22.5;2026-11-01T17:00:03;2;2,0,0
```

| Campo | Significado |
|---|---|
| endereço, porto | Serviço TCP do líder |
| inc | Instante de arranque do líder em milissegundos; identifica a incarnação |
| hb | Número do heartbeat dentro dessa incarnação |
| ultimoSeq | Último seq atribuído quando o lote foi fechado |
| n | Quantidade de linhas de registo a seguir ao cabeçalho |

`hb` conta mensagens periódicas; `seq` conta registos. Um lote vazio pode ter hb=50 e ultimoSeq=2. Não são dois nomes para o mesmo contador.

`Batch.decode(msg, n)` recebe a dimensão dos vetores no argumento `n`, e compara o número de linhas com a quantidade indicada no cabeçalho. Valida porto, números e sequência crescente dos registos. `Batch.header` permite ao produtor interpretar só o cabeçalho. O produtor não analisa nem incorpora L/V.

## 7. Onde o líder cria um evento

Em `PartitionLeader.processInMessage`, a secção `synchronized (this)` calcula os próximos L, V e seq, verifica V[k]=seq, escreve o registo e atualiza o estado. Só depois de um append bem-sucedido avança o estado em memória. O mesmo registo entra em `pendentes` ainda nessa secção.

Os relógios continuam a seguir a COMINDIR03:

```text
L = max(L local, L recebido) + 1
V = máximo por componente entre V local e V recebido
V[k] = V[k] + 1
seq = seq + 1
```

Um heartbeat não cria um novo registo, por isso não incrementa L ou V. Se incrementasses V[k] nos lotes vazios, deixaria de corresponder ao número de registos da partição. `CausalCheck` verifica esta invariante nos registos persistidos; não consegue observar uma alteração em memória se nunca se seguir outro registo.

## 8. Onde o líder fecha e publica o lote

Em `enviarLote`, usando o mesmo monitor do método de entrada, o líder troca `pendentes` por uma lista nova, captura seq e incrementa hb. O objeto `Batch` conserva uma cópia da lista fechada. Depois liberta o monitor e publica.

Imagina que trocava a lista e só depois lia seq sem proteção: uma entrada poderia ser escrita entretanto. O cabeçalho anunciaria um registo que pertence ao próximo lote, levando as réplicas a pedir uma recuperação prematura. Proteger a troca e a captura em conjunto dá um snapshot coerente.

A chamada HTTP fica fora do bloqueio porque pode demorar. Se estivesse dentro, bloquearia a receção de novas entradas e a consulta do progresso pelo TCP durante essa espera.

O temporizador usa `scheduleAtFixedRate`. Depois de uma pausa longa, execuções em atraso podem ocorrer próximas umas das outras, sem execução simultânea na única thread do temporizador. `scheduleWithFixedDelay` esperaria um período completo depois de cada execução; uma publicação de 2 s com atraso de 5 s produziria um intervalo de cerca de 7 s.

Se publicar falhar, o líder regista o erro e não reinsere o lote. Os dados continuam no ficheiro, e um lote seguinte anuncia o progresso através de `ultimoSeq`. Se o próprio registo do log hb falhar depois da publicação, os participantes podem ter recebido um lote ausente do log: o erro é apresentado e as medições devem ser interpretadas com essa limitação.

## 9. O detetor e os seus três estados

`DESCONHECIDO`: ainda não foi observado um heartbeat válido. `ATIVO`: recebeu-se um heartbeat recente. `SUSPEITO`: o silêncio ultrapassou o timeout.

`onHeartbeat(inc, hb)` corre na thread de receção. Ignora incarnações antigas e heartbeats repetidos/atrasados. Uma mudança de inc gera `REINICIO`; um salto de hb na mesma inc gera `HB_PERDIDO`; uma receção válida depois de suspeita gera `RETOMA`. Depois atualiza o instante local e passa a ATIVO.

`check()` corre periodicamente e compara `System.nanoTime()` com o instante da última receção. Só emite uma SUSPEITA por transição. O monitor também pode suspeitar sem nunca ter recebido um heartbeat, caso o arranque dos líderes demore demasiado; nesse caso não há inc conhecida para classificar a falha.

`nanoTime` mede durações locais e não é um relógio de calendário. Não se compara o nanoTime de duas JVMs. Os logs usam `currentTimeMillis` para que os processos da mesma máquina possam ser comparados na análise posterior.

## 10. Completude, exatidão e tempo de deteção

**Completude:** um líder que falha acaba por ser suspeito, desde que o participante correto continue a executar as verificações. **Exatidão:** não suspeitar de um líder correto. Um timeout não garante exatidão num ambiente sem limite conhecido para os atrasos.

Uma pausa da JVM, ou um atraso no daemon Kubo, pode atrasar a chegada de heartbeats sem o líder ter terminado. O estado chama-se SUSPEITO precisamente porque não é uma prova de morte.

Com Δ=5 s, fator=3 e verificação de 1 s, o timeout é 15 s. Se a falha ocorrer quase imediatamente após o último heartbeat, a deteção demora aproximadamente 15–16 s. Se ocorrer quase no fim do intervalo até ao heartbeat seguinte, demora aproximadamente 10–11 s. O intervalo teórico é **]10 s, 16 s]**, ignorando atrasos da rede e do escalonamento.

Diminuir o fator acelera suspeitas e aumenta o risco de falsas suspeitas. Aumentá-lo tolera mais atrasos, mas demora mais a detetar falhas. Não há um valor que elimine esse compromisso num sistema inteiramente assíncrono.

## 11. O último registo perdido

Suponhamos que a réplica tem seq=2. O líder recebe a leitura seguinte, grava seq=3, mas termina antes de publicar o lote. Não chega seq=4 que denuncie uma lacuna.

No reinício, o líder restaura seq/L/V do último registo do seu ficheiro, cria uma nova incarnação e publica um lote vazio com ultimoSeq=3. A réplica compara o seu último seq, 2, com 3. Pede `PEDIDO 3 3` por TCP e aplica a resposta.

É por isso que `ultimoSeq` precisa de existir mesmo num lote vazio. O heartbeat informa sobre vida; o campo ultimoSeq informa sobre progresso persistido.

## 12. Aplicar, reter, recuperar

`PartitionReplica.PartitionState.onPubSubMessage` descodifica o lote, renova o monitor, regista RECEBIDO e percorre os registos pela ordem. `applyRule` conserva a regra anterior: ignora duplicados, aplica o próximo seq e retém os que estão à frente de uma lacuna.

Depois do lote, se `last < batch.lastSeq`, chama `recover(batch.lastSeq)`. O método abre TCP, pede o intervalo em falta, valida os registos e espera `FIM`. Se falhar, regista a falha; um lote seguinte volta a permitir detetar a diferença. `RECUPERACAO` documenta um pedido, não prova por si só que todos os registos foram recuperados: confirma os ficheiros e os logs.

O detetor pode ignorar um heartbeat antigo sem descartar os registos que transporta. A réplica continua a usar seq para decidir quais são úteis. Um estado SUSPEITO também não impede aplicar dados.

## 13. O que fazem produtor e processador quando há suspeita?

O produtor avisa antes de enviar uma leitura para um líder suspeito, mas envia-a na mesma. O processador faz o mesmo com um alarme. Não há fila persistente de reenvio, confirmação de entrega ou promoção de uma réplica.

O processador continua a acumular máximos de L/V das leituras originais e a conservar conjuntos de seq já vistos por partição. Não faz tick; o tick pertence ao líder que cria o registo do alarme. Na implementação especificada da secção 4.1, a recuperação TCP é feita pelas réplicas; o produtor só lê cabeçalhos e o processador trata os registos que recebe. Não há recuperação histórica de alarmes perdidos.

## 14. Ler os ficheiros

| Ficheiro | O que demonstra |
|---|---|
| `lider-p0.txt` | Registos aceites e persistidos pelo líder p0 |
| `replica-1-p0.txt` | Cópia aplicada pela réplica 1 |
| `hb-p0.txt` | inc;hb;instante;ultimoSeq;n;bytes das publicações concluídas |
| `monitor-replica1.txt` | Receções e eventos do detetor da réplica 1 |
| `monitor-processador.txt` / `monitor-produtor.txt` | Eventos dos restantes participantes |
| `falhas.txt` | Instantes anotados ao provocar falhas reais |
| `heartbeat-check.txt` | Métricas dos lotes, suspeitas, recuperação e latência |

O instante no log hb é capturado imediatamente antes da publicação; o append do log só ocorre se a publicação terminar com sucesso. RECEBIDO é registado antes de aplicar os dados: a latência calculada é até à receção do lote, não até ao fim da escrita da réplica.

## 15. O que HeartbeatCheck consegue medir

Agrupa publicações por partição e incarnação. Calcula quantidade, proporção de lotes vazios, dimensão média/máxima e intervalos entre publicações. Não calcula intervalos atravessando reinícios.

Relaciona cada SUSPEITA com a incarnação conhecida. Uma RETOMA com a mesma inc identifica uma falsa suspeita. Sem novo heartbeat publicado dessa inc depois da suspeita, aplica a classificação de falha real pedida na ficha, limitada à janela de observação. Se houver publicações posteriores mas faltar a RETOMA correspondente, assinala INCONCLUSIVA. Uma captura terminada demasiado cedo não prova uma falha.

Com `falhas.txt`, relaciona a suspeita com a falha anotada dessa incarnação e calcula a diferença. Sem esse ficheiro, só consegue calcular silêncio desde o heartbeat publicado, que não é o mesmo que tempo desde a falha.

A latência usa a primeira receção de cada registo em cada participante e o instante do registo do líder. Não inventa uma latência de lote para um registo obtido apenas por TCP. A média agrupa essas observações por partição. Os bytes são UTF-8 das mensagens da aplicação, sem cabeçalhos HTTP, TCP ou IPFS. O custo LOTE inclui o tráfego dos lotes vazios; o REG equivalente usa o endereço local da demonstração.

## 16. Porque a eficiência depende da carga

Com muitos registos num intervalo, um cabeçalho é partilhado e há menos publicações. Com o produtor parado, todos os lotes são vazios: existe tráfego para detetar vida e nenhum registo por que repartir esse custo. Assim, um ensaio muito ocioso pode gastar mais bytes por registo do que REG individuais.

A espera média de Δ/2 pressupõe chegadas aproximadamente uniformes ao longo dos intervalos. Uma rajada curta junto ao início ou ao fim de um intervalo pode dar um valor muito diferente. Não ajustes os resultados para os fazer coincidir com a fórmula.

## 17. Ensaios da ficha no Windows

Para (a), segue os tempos relativos da figura usando S01/S04 e `falhar 0`; confirma primeiro que o último registo entrou em `lider-p0.txt`, mas ainda não apareceu no lote. Reinicia na mesma pasta para conservar o histórico.

Para (b), guarda uma sessão com `rajada 300 10` e outra com o produtor parado durante um minuto. Para (c), provoca a falha de p1 durante a rajada, espera 30 s, reinicia e repete três vezes, conservando os instantes em `falhas.txt`. Para (d), usa `pausar 2`, envia leituras para p2, espera 20 s e usa `retomar 2`; observa se houve entrega posterior ou perda, sem assumir antecipadamente o resultado.

Para (e), repete as sessões com os três períodos no README. O controlador lê automaticamente os parâmetros da sessão para o reinício. Não mistures períodos na mesma pasta se quiseres comparar métricas sem segmentação adicional.

As mutações deliberadas da ficha devem ser feitas uma de cada vez numa cópia de trabalho, compiladas e revertidas antes da seguinte. O relatório não apresenta comportamentos previstos como se fossem medições.

## 18. Limites que tens de saber explicar

Se uma leitura se perder antes do líder a aceitar, não recebeu seq e não deixou uma lacuna. Os relógios e ultimoSeq não revelam uma leitura desconhecida do líder. Resolver isso exigiria identificação de entradas, confirmações e reenvio com tratamento de duplicados.

Detetar não substitui o líder. Promover réplicas independentemente poderia criar dois líderes a atribuir o mesmo seq. Eleição, coordenação e controlo de épocas seriam trabalho adicional, fora do âmbito.

As réplicas não fazem entrega causal entre partições; preservam os carimbos e ordenam por seq dentro de cada partição. A análise compara relógios físicos da mesma máquina, mas o detetor usa apenas durações locais. Em máquinas diferentes, as medições de latência exigiriam hipóteses adicionais sobre o desvio dos relógios.

## 19. Mapa para mostrar o código ao professor

| Pergunta | Onde procurar |
|---|---|
| Como se escolhe a partição? | `Partitioner.of` |
| Onde se forma o evento e entra em pendentes? | `PartitionLeader.processInMessage` |
| Onde se fecha/publica o lote? | `PartitionLeader.enviarLote` |
| Onde é configurado o período? | `Wire` e `PartitionLeader.start` |
| Onde se decide suspeitar? | `HeartbeatMonitor.check` |
| Onde se ignoram heartbeats antigos? | `HeartbeatMonitor.onHeartbeat` |
| Onde se recupera o último registo? | `PartitionReplica.PartitionState.onPubSubMessage` e `recover` |
| Onde se preserva conhecimento causal? | `AlarmProcessor.onRecord` |
| Onde se gera uma rajada? | Ciclo de comandos de `Producer.main`; não existe `burstTimer` nesta base |
| Onde se medem os resultados? | `HeartbeatCheck.main` |

## 20. Perguntas de revisão com aplicação

Se aumentares o número de réplicas, o vetor não muda: continua a ter uma posição por líder. Se aumentares N, mudam o particionamento e a dimensão/interpretação do vetor, pelo que não se reutiliza cegamente o histórico.

Se o heartbeat passar de 5 s para 10 s mantendo o fator 3, o timeout passa de 15 s para 30 s, a espera máxima do lote aumenta e a deteção teórica passa para ]20 s,31 s].

Se um lote antigo chegar depois de um reinício, não renova o detetor, mas pode trazer um seq ainda útil para a réplica. Se chega um lote vazio com ultimoSeq maior que last, há trabalho a recuperar mesmo sem linhas no lote.

Fecha o guia e explica o percurso de S02 29.3, indicando as duas esperas, os dois eventos e os três tipos de contador: seq, hb e L/V. Depois mostra no código as secções sincronizadas. Essa explicação, acompanhada de logs reais, responde ao problema apontado no feedback anterior: reconhecer o código e justificar o papel das threads.

## 21. Inventário das classes e dos métodos

Esta secção serve para localizar e explicar cada parte da implementação. As classes comuns têm cópias iguais nos projetos que as incluem; basta estudar uma dessas cópias. Os construtores preparam objetos; os métodos executam operações sobre esses objetos. Os comentários do código resumem a função, enquanto este inventário explica a ligação ao sistema.

### Partitioner — escolher a partição

Código: [Partitioner.java](rep04-lider/src/rep04/Partitioner.java).

| Método | O que faz e porquê |
|---|---|
| `Partitioner(n)` | Guarda o número de partições e rejeita dimensões inválidas. Todos devem usar o mesmo N. |
| `of(sensorId)` | Calcula `floorMod(sensorId.hashCode(), n)`, produzindo um índice não negativo. O mesmo nome e N dão sempre a mesma partição. |
| `count()` | Devolve N, usado nos ciclos e na dimensão dos vetores. |

### Wire — parâmetros e protocolo

Código: [Wire.java](rep04-lider/src/rep04/Wire.java).

| Elemento | Função |
|---|---|
| `DEFAULT_N`, `PORTO_BASE` | Fixam os valores por omissão: três partições e TCP a partir de 7000. |
| `LIMITE_RETIDOS`, `T_TCP_MS` | Dez registos retidos como limiar anterior de recuperação; 5000 ms de timeout TCP. Recupera ao **ultrapassar** dez, ou pela comparação final com ultimoSeq. |
| `LIMIAR_ALARME` | Temperaturas de pelo menos 28 originam alarmes. |
| `PERIODO_HB_MS`, `FATOR_TIMEOUT`, `VERIFICACAO_MS` | Período de publicação, multiplicador do timeout e intervalo de verificação. O bloco estático valida valores positivos e a multiplicação para nanossegundos. |
| `Wire()` | Construtor privado: esta classe é usada através de constantes e métodos estáticos. |
| `topicIn(k)`, `topicData(k)` | Constroem os nomes dos tópicos de entrada e de lotes da partição. |
| `validateReading(sensor, temp)` | Rejeita nomes vazios, separadores/caracteres de controlo e temperaturas não finitas. Evita mensagens ambíguas. |
| `in(sensor, temp, l, v)` | Produz a mensagem IN com a leitura e o conhecimento causal transportado. |
| `inParts(msg)` | Confirma o tipo e os cinco campos; devolve sensor, temperatura, L e V em texto. A interpretação numérica é feita pelo líder. |
| `type(msg)`, `body(msg)` | Auxiliares herdados para separar tipo e conteúdo. Permanecem disponíveis, mas o fluxo principal usa `inParts` e `Batch`. |

### SensorRecord — um evento persistido

Código: [SensorRecord.java](rep04-lider/src/rep04/SensorRecord.java).

| Método | Função |
|---|---|
| Construtor | Valida seq, sensor, temperatura, instante e Lamport; guarda uma cópia do vetor para proteger o evento de alterações posteriores. |
| `now(...)` | Cria um registo com a data/hora local atual. O líder já calculou seq/L/V antes de o chamar. |
| `toLine()` | Converte para `seq;sensor;temperatura;instante;L;V`, formato comum ao disco, lote e recuperação TCP. |
| `fromLine(line)` | Interpreta uma linha usando N=3 por omissão. |
| `fromLine(line, n)` | Interpreta os seis campos e valida a dimensão do vetor indicado. |
| `getSeq`, `getSensorId`, `getTemperature`, `getTimestamp`, `getLamport` | Consultam os dados do evento, sem os modificar. |
| `getVector()` | Devolve uma cópia, para o chamador não alterar o vetor guardado. |

### VectorClock — conhecimento causal

Código: [VectorClock.java](rep04-lider/src/rep04/VectorClock.java).

| Método | Função |
|---|---|
| `VectorClock(n)` | Cria N componentes a zero. O construtor privado com um array é usado internamente na cópia e leitura. |
| `sameSize(other)` | Impede operações entre vetores de dimensões diferentes. |
| `merge(other)` | Atualiza cada componente para o máximo local/recebido. Incorpora conhecimento, sem criar por si só um evento local. |
| `tick(k)` | Incrementa a componente do líder que cria o evento, detetando overflow aritmético. |
| `get(k)`, `size()` | Consultam uma componente e a dimensão. |
| `copy()` | Clona o array para obter um snapshot independente. |
| `compare(other)` | Devolve BEFORE, AFTER, EQUAL ou CONCURRENT a partir da comparação por componentes. |
| `toString()`, `parse(text, n)` | Convertem entre o vetor e números separados por vírgulas; a leitura rejeita dimensões erradas e valores negativos. |
| `equals(obj)`, `hashCode()` | Comparam valores e fornecem um código coerente para coleções Java. Não substituem a comparação causal. |

### RecordFile — ler e escrever históricos

Código: [RecordFile.java](rep04-lider/src/rep04/RecordFile.java).

| Método | Função |
|---|---|
| `RecordFile(name)` / `RecordFile(name, n)` | Associam um caminho à dimensão dos vetores; o primeiro usa a dimensão por omissão. |
| `append(record)` | Acrescenta uma linha UTF-8, criando o ficheiro se necessário. Não substitui o histórico anterior. |
| `last()` | Percorre as linhas e devolve o último registo; devolve null num histórico ausente/vazio. Uma linha inválida causa erro. |
| `lastSeq()` | Devolve o seq do último registo, ou zero. |
| `range(from, to)` | Lê os registos entre os limites inclusivos, para responder à recuperação TCP. |

As operações sobre o mesmo objeto são sincronizadas. Isso coordena as threads do processo; não é um protocolo de bloqueio entre programas independentes. O append não constitui, por si só, uma garantia contra falha física do disco ou corte de energia.

### IpfsPubSub — adaptação ao Kubo

Código: [IpfsPubSub.java](rep04-lider/src/rep04/IpfsPubSub.java).

| Método | Função |
|---|---|
| Construtor | Normaliza o endereço da API. O objeto usa um HttpClient com timeout de ligação de cinco segundos. |
| `toMultibase(text)` | Converte UTF-8 para base64url com prefixo `u`, usado no nome do tópico. |
| `fromMultibase(text)` | Valida esse prefixo e descodifica o conteúdo recebido. |
| `publish(topic, message)` | Cria um POST multipart para `/pubsub/pub`, espera pela resposta HTTP e rejeita estados diferentes de 200. O pedido tem timeout de dez segundos. |
| `subscribe(topic, onMessage)` | Mantém o POST `/pubsub/sub` aberto, lê as linhas da resposta, extrai `data`, descodifica e chama a função recebida. |

`onMessage` é uma função passada como argumento, não uma nova thread criada por mensagem. O tratamento corre na thread que lê essa subscrição. Uma resposta HTTP de sucesso na publicação não confirma que todas as réplicas já guardaram o registo. A subscrição não tem um timeout total curto porque deve permanecer aberta; se terminar, é lançado um erro e os programas apresentam a falha.

### Batch — cabeçalho e lista de registos

Código: [Batch.java](rep04-lider/src/rep04/Batch.java).

| Método | Função |
|---|---|
| Construtor público | Recebe endereço, porto, inc, hb, ultimoSeq e registos; calcula a quantidade a partir da lista. |
| Construtor privado | Valida os campos e conserva uma lista imutável. Também permite representar apenas um cabeçalho, com quantidade anunciada e sem descodificar o corpo. |
| `encode()` | Constrói o cabeçalho e acrescenta uma linha por registo. |
| `header(msg)` | Lê somente a primeira linha. É o caminho usado pelo produtor. |
| `decode(msg, n)` | Lê todo o lote, confirma a quantidade, valida os vetores e exige seq crescentes e não superiores a ultimoSeq. |

O argumento `n` de `decode` é a dimensão dos vetores; o campo `n` do protocolo é a quantidade de registos. A lista é copiada, mas os registos podem ser partilhados porque não expõem alterações aos seus campos. `header` produz uma representação para consulta; o produtor não a usa para voltar a publicar um lote.

### HeartbeatMonitor e MonitorLog — observar e registar

Código: [HeartbeatMonitor.java](rep04-lider/src/rep04/HeartbeatMonitor.java) e [MonitorLog.java](rep04-lider/src/rep04/MonitorLog.java).

| Método | Função |
|---|---|
| `HeartbeatMonitor(...)` | Guarda partição, timeout em nanossegundos e a função de log; inicia o instante de referência local. |
| `onHeartbeat(inc, hb)` | Filtra mensagens antigas/repetidas, deteta REINICIO/HB_PERDIDO/RETOMA e renova ATIVO. |
| `check()` | Mede o silêncio e emite SUSPEITA apenas quando muda para esse estado. |
| `state()` | Consulta o estado com a mesma proteção sincronizada. |
| `MonitorLog(id)` | Escolhe `monitor-<id>.txt`. |
| `event(text)` | Acrescenta o instante físico, escreve no ficheiro e mostra na consola. Uma falha de escrita é propagada. |
| `received(k, batch)` | Regista inc, hb, ultimoSeq, quantidade e intervalo de seq. No produtor, deduz o intervalo do cabeçalho, pois os lotes normais fecham registos consecutivos. |
| `schedule(monitors)` | Cria uma thread de verificação por chamada e chama `check` a cada segundo. Cada réplica passa todos os seus monitores numa única chamada. |

Os quatro projetos contêm as classes comuns, conforme a ficha. O líder publica heartbeats, mas não usa um monitor para vigiar os outros líderes. Uma thread marcada como daemon não mantém a JVM viva quando as restantes threads terminam.

### PartitionLeader — aceitar, fechar e recuperar

Código: [PartitionLeader.java](rep04-lider/src/rep04/PartitionLeader.java).

| Método | Função |
|---|---|
| Construtor | Valida a partição, prepara Kubo/TCP/ficheiro e restaura seq, L e V. Verifica a invariante antes de aceitar trabalho. |
| `getLastSeq()` | Consulta seq com o monitor do líder; é usado também pelo serviço TCP. |
| `processInMessage(msg)` | Valida IN e a partição, calcula o próximo estado, persiste o evento e coloca-o em pendentes, sob o mesmo bloqueio. |
| `enviarLote()` | Fecha o snapshot, incrementa hb e publica fora do bloqueio; acrescenta o log hb se a publicação concluir. |
| `start()` | Abre o porto TCP, cria a thread de entradas e o temporizador de lotes. O ciclo principal aceita pedidos TCP e responde com linhas e FIM. |
| `main(args)` | Lê k, N, endereço/API opcionais e inicia o líder. Erros fatais terminam o processo. |

O servidor TCP atende as ligações sequencialmente nesta implementação. Valida o pedido e limita o fim do intervalo ao último seq existente. Manter a recuperação como antes facilita reconhecer a continuidade com a COMINDIR02/03.

### PartitionReplica — uma sequência por partição

Código: [PartitionReplica.java](rep04-replica/src/rep04/PartitionReplica.java).

| Método | Função |
|---|---|
| `PartitionState(...)` | Prepara ficheiro, última sequência, retidos e monitor de uma partição. |
| `onPubSubMessage(msg)` | Valida o lote e a partição dos registos, aplica a simulação opcional de perdas, regista receção e aplica/recupera os dados. |
| `applyRule(record, fromRecovery)` | Ignora duplicados, aplica o próximo seq ou retém o registo adiantado. O indicador evita desencadear recuperação recursiva durante uma recuperação. |
| `writeRecord(record)` | Faz append e avança last; apresenta contadores de diagnóstico. |
| `drainPending()` | Aplica sucessivamente os retidos que agora têm seq=last+1. |
| `recover(ate)` | Pede desde last+1 até ate, valida as linhas e aplica-as. Confirma a marca FIM e apresenta erros de comunicação. |
| `main(args)` | Valida id/partições, cria uma thread de subscrição por partição e uma thread que verifica todos os seus monitores. |

As propriedades opcionais `-Dperda=0.2` e `-Ddescartar=2`, colocadas antes de `-cp` numa execução manual, simulam perdas na receção PubSub. Na COMINDIR04, `descartar` compara com o **número hb do lote**; o nome interno `seqDescartar` foi herdado do código anterior. Não atua nas respostas TCP. Esses testes não são ativados automaticamente pelo CMD.

### AlarmProcessor — transformar uma leitura em alarme

Código: [AlarmProcessor.java](rep04-processador/src/rep04/AlarmProcessor.java).

| Método | Função |
|---|---|
| Construtor | Cria o vetor, os conjuntos de duplicados por partição, os monitores e a verificação periódica. |
| `onMessage(k, msg)` | Valida o lote, atualiza o monitor e passa cada registo a `onRecord`. |
| `onRecord(k, record)` | Ignora alarmes para não gerar ciclos e ignora leituras duplicadas. Incorpora L/V, copia o conhecimento e publica um alarme se atingir o limiar. |
| `start()` | Arranca uma subscrição por partição e espera pelas respetivas threads. |
| `main(args)` | Lê N/API e inicia o processador. |

Mesmo leituras abaixo do limiar atualizam o conhecimento. A secção sincronizada reúne a verificação de duplicados, o merge e a captura de L/V. A publicação ocorre depois. Se falhar, não há repetição automática: o registo já está no conjunto de observados, o que é uma limitação desta implementação.

### Producer — comandos, rajadas e avisos

Código: [Producer.java](rep04-produtor/src/rep04/Producer.java).

| Método | Função |
|---|---|
| `warn(monitor, k, sensor)` | Apresenta o aviso de destino suspeito; não decide cancelar o envio. |
| `main(args)` | Lê N/API, cria subscrições dos cabeçalhos e monitores, lê comandos do teclado e publica entradas. |

Dentro de `main`, a função de cada subscrição trata apenas o cabeçalho. O ciclo de comandos valida entradas individuais. O ciclo de rajada escolhe sensores S01–S10, gera temperaturas entre 15 e 30 e espera o intervalo pedido entre envios. `rajada-quente` favorece S01 na escolha do sensor: **não significa que todas as temperaturas ultrapassam o limiar de alarme**. Todas as entradas originais levam L/V a zero, mesmo agora que o produtor recebe cabeçalhos de heartbeat.

### Os três verificadores

Código: [PartitionCheck.java](rep04-replica/src/rep04/PartitionCheck.java), [CausalCheck.java](rep04-replica/src/rep04/CausalCheck.java) e [HeartbeatCheck.java](rep04-replica/src/rep04/HeartbeatCheck.java).

| Classe e método | Função |
|---|---|
| `PartitionCheck.readRecords(path)` | Lê os registos existentes; trata ausência de ficheiro como lista vazia. |
| `PartitionCheck.checkSequence(records)` | Confirma uma sequência começada em 1 e sem saltos. |
| `PartitionCheck.main(args)` | Verifica sensores/partições, procura as réplicas e compara cada histórico: igual, prefixo ou divergente. |
| `CausalCheck.Event(...)` / `id()` | Associam evento, partição e vetor; criam o identificador p&lt;k&gt;#seq. |
| `CausalCheck.violation(reason)` | Conta violações e limita os diagnósticos mostrados aos primeiros vinte. |
| `CausalCheck.check(n, dir)` | Verifica seq e V[k]=seq, crescimento dos relógios e relações entre vetores; calcula estatísticas e escreve a vista ordenada por L, k e seq. |
| `CausalCheck.main(args)` | Valida argumentos, chama a análise e termina com erro se encontrar violações ou não conseguir analisar. |
| `HeartbeatCheck.HB(line)` | Lê uma linha do log de publicações: inc, hb, instante, ultimoSeq, quantidade e bytes. |
| `HeartbeatCheck.Event(line)` / `value(key)` | Interpretam uma linha do monitor e os seus detalhes numéricos. |
| `HeartbeatCheck.Stats.add(value)` / `result()` | Acumulam quantidade, soma e máximo e apresentam estatísticas. |
| `HeartbeatCheck.lines(path)` | Lê linhas não vazias, remove BOM e tolera ficheiros ausentes. |
| `HeartbeatCheck.main(args)` | Agrupa lotes, relaciona suspeitas/retomas, cruza falhas anotadas, calcula latências/custos e escreve o relatório. |

O resumo de `PartitionCheck` deve ser lido: terminar normalmente não prova que as cópias estavam completas. `HeartbeatCheck` também apresenta métricas, não um certificado universal de correção. O resultado depende de logs completos e de uma janela suficiente para observar retomas.

## 22. O que fazem os dois CMD

`iniciar-kubo.cmd` procura `ipfs` no PATH e executa `ipfs daemon`. Não instala o programa nem muda a configuração em cada arranque.

`testar.cmd` contém o apoio PowerShell dentro do próprio ficheiro. Resolve o executável real do JDK, compila os quatro projetos e executa testes de Batch/HeartbeatMonitor incluídos no CMD. Guarda os resultados numa sessão em `%TEMP%`, preservando a organização da pasta do projeto.

Depois verifica a API e o PubSub, arranca réplicas, líderes, processador e produtor e espera as subscrições dos processos de fundo. Ao sair, aguarda dois períodos e uma margem, termina os processos da sessão e corre os verificadores. O código Java continua a funcionar independentemente desse lançador.

O modo `-Controlar` usa os PIDs e a configuração da sessão. `falhar` termina abruptamente o líder e anota o instante; `reiniciar` conserva o histórico na mesma pasta; `pausar`/`retomar` suspendem/retomam o processo Windows sem criar outra incarnação. Estas ações equivalem ao objetivo dos comandos de falha e suspensão indicados na ficha para Unix.

## 23. Guião para a apresentação

1. **Problema:** explica o que faltava à COMINDIR03: silêncio ambíguo e perda do último registo sem um sucessor que revele a lacuna.
2. **Evolução:** distingue replicação da COMINDIR01, particionamento/recuperação da 02, causalidade da 03 e heartbeats/lotes da 04.
3. **Uma leitura:** mostra S02 29.3, a partição p1, o alarme em p2 e os carimbos do exemplo. Identifica as duas esperas nos lotes.
4. **Código:** mostra `processInMessage`, `enviarLote`, `onHeartbeat`, `check` e a comparação `last < batch.lastSeq` na réplica.
5. **Concorrência:** aponta as threads e explica por que a troca de pendentes e a leitura de seq usam o mesmo monitor.
6. **Falha:** mostra SUSPEITA, REINICIO, RETOMA e a recuperação TCP, distinguindo um pedido de recuperação de uma cópia confirmada.
7. **Resultados:** interpreta as unidades e a origem das medições; apresenta apenas os ensaios que realizaste.
8. **Limites:** explica por que não há failover, garantia de entrega de entradas ou exatidão incondicional do detetor.

## 24. Perguntas para confirmar que consegues defender o trabalho

Tenta responder antes de ler a justificação. Se bloqueares, regressa ao método indicado e usa um exemplo próprio.

| Pergunta | Resposta que deves conseguir justificar |
|---|---|
| p0 está em seq=7 e envia três lotes vazios. Qual é seq? | Continua 7; hb avança três vezes, L/V não avançam por causa desses lotes. |
| A réplica está em seq=7 e recebe lote vazio com ultimoSeq=9. O que faz? | Pede 8..9 por TCP; não precisa de receber o registo 10 para descobrir a falta. |
| A réplica recebeu hb=8 e agora recebe hb=8 outra vez. Renova o timeout? | Não. O monitor ignora duplicados; os registos continuam sujeitos à regra de seq. |
| Retoma com inc igual prova um reinício? | Não. É a mesma incarnação, compatível com uma pausa/atraso; uma nova inc assinala reinício. |
| Se mudares o fator para 1, consegues provar mais depressa que o líder morreu? | Produzes uma suspeita mais cedo, com menos tolerância a atrasos. Não provas a morte. |
| Porque copiar o vetor antes de publicar um alarme? | Outra thread pode atualizar o conhecimento; a cópia conserva o vetor correspondente ao snapshot de L. |
| Porque ter um conjunto de seq vistos por partição? | p0#1 e p1#1 são eventos distintos; um conjunto global só de seq confundia-os. |
| Um IN perdido antes do líder deixa uma lacuna de seq? | Não: ainda não lhe foi atribuído seq. A recuperação do histórico não o reconstrói. |
| A média da rajada tem obrigatoriamente de ser Δ/2? | Não. Essa aproximação depende da distribuição das chegadas; uma rajada concentrada não é uniforme. |
| Porque as quatro suspeitas da mesma falha não significam quatro falhas? | Cada participante decide localmente; há quatro observações de um único acontecimento. |
| O que falta para terminar a parte experimental? | Três falhas de p1 durante rajadas, a comparação completa de períodos e as mutações, com medições e reflexão. |

Se explicares estes pontos por palavras tuas, os relacionares com os métodos e conseguires reproduzir a demonstração, o README dá-te uma base de preparação. Não garante uma nota nem elimina a necessidade de completar os ensaios pedidos pelo professor.
