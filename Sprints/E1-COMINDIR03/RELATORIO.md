# COMINDIR03 — Resultados

Ensaios de 25-10-2026, em Windows, com JDK 21.0.10, compilação para Java 11 e Kubo 0.39.0. N=3, R=2 e um daemon IPFS local. Cada ensaio começou com ficheiros novos; as três rajadas com processador foram execuções independentes.

## 7 — Ensaios

### (a) Exemplo da ficha

| Evento | Partição e seq | Sensor | L | V |
| --- | --- | --- | --- | --- |
| a | p1#1 | S02 | 1 | 0,1,0 |
| b | p0#1 | S01 | 1 | 1,0,0 |
| c | p2#1 | ALARM-S02 | 2 | 0,1,1 |
| d | p0#2 | S04 | 2 | 2,0,0 |

Foram introduzidos S02 29.3, S01 21.0 e S04 22.5, esperando pelo alarme antes de introduzir S01. Assim, o processador ainda não tinha observado b ao publicar c. Os carimbos coincidiram com os da figura; nesta execução c ocorreu antes de b, que é concorrente com c.

`CausalCheck`: cinco pares entre partições, um causal, quatro concorrentes e dois concorrentes com L distinto (50%). A relação b→d pertence à mesma partição e não entra nessa contagem. A vista por (L,k) foi b, a, d, c.

### (b) Sem processador

Com `rajada 300 10`, obtiveram-se 122 / 88 / 90 registos em p0/p1/p2, zero alarmes, zero pares causais entre partições e 29636 pares concorrentes. Destes, 29370 tinham L distinto (99.102%). Os vetores apenas acumularam eventos do próprio líder.

### (c) Com processador

| Execução | Registos p0 / p1 / p2 | Alarmes | Pares causais | Pares concorrentes | Concorrentes com L distinto | Proporção |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 130 / 100 / 104 | 34 | 31297 | 5623 | 5374 | 95.572% |
| 2 | 135 / 115 / 92 | 42 | 32285 | 6240 | 5978 | 95.801% |
| 3 | 139 / 106 / 104 | 49 | 35135 | 5079 | 4804 | 94.586% |

Cada execução usou `rajada 300 10`. As leituras com temperatura ≥28 °C originaram um alarme cada, sem alarmes sobre alarmes. Verificaram-se V[k]=seq, a condição de Lamport e a igualdade das seis cópias das partições nas réplicas com os respetivos líderes. `CausalCheck` terminou sem violações.

### (d) Reinício de p2

Antes do reinício: seq=1, L=2, V=0,1,1. Depois de terminar o líder e o arrancar na mesma pasta, S03 21.0 recebeu seq=2, L=3, V=0,1,2. O estado foi recuperado da última linha, mantendo a dependência de p1. As réplicas convergiram e `CausalCheck` terminou sem violações.

### (e) Ordem de escrita na réplica 1

A réplica 1 usou `-Ddescartar=1`, uma vez por partição. Primeiro enviaram-se 12 leituras frias S02 para consumir o descarte de p1 e recuperar essa partição. Depois S01 29.3 criou p0#1 e o alarme p1#13. A origem foi descartada em p0, mas o alarme foi escrito em p1. Onze leituras posteriores de p0 desencadearam a recuperação por TCP.

No log da mesma réplica, o alarme foi escrito em t=1792967886502 ms e a origem em t=1792967886553 ms: o alarme foi escrito 51 ms antes. Estes são os valores `t=` das mensagens `APLICADO`; o campo `instante` do registo é atribuído pelo líder. No fim, as réplicas ficaram iguais aos líderes. A ordem por partição não garante entrega causal entre partições.

## 8 — Falhas

As falhas foram executadas separadamente em cópias do código, conservando a versão correta.

| Falha | Lado onde se manifestou | Detetada por CausalCheck? | Conclusão |
| --- | --- | --- | --- |
| Omitir o merge de Vmsg | Líder p2: alarme com V=0,0,1 | Não | A dependência de p1#1 desapareceu, embora V[2]=seq continue verdadeiro. O verificador não conhece o IN original. |
| Ignorar Lmsg | Líder p2: L=1, V=0,1,1 | Sim | O vetor indica p1#1→p2#1, mas ambos têm L=1, violando a condição de relógio. |
| Reiniciar L e V a zero | Arranque de p2 com seq persistido | Não nos ficheiros; o líder recusou o arranque | V[k] deixou de coincidir com seq. A verificação impediu a escrita de um evento incorreto. |
| Usar V sem copy | Processador: L antigo e vetor alterado por outra thread | Sim, com intercalação controlada | O alarme ficou com L=2 e V=5,1,1, incluindo eventos de p0 com L≥2. Sem controlar a intercalação, a corrida pode não se manifestar em todas as execuções. |
| Produtor N=4 e restantes N=3 | Entrada de p0 rejeitada por dimensão; p3 sem líder | Não | As leituras não geraram eventos, pelo que não existe um registo inválido para analisar. |

## 9 — Médias das três execuções

| Métrica | Valor médio |
| --- | --- |
| Registos por partição (p0 / p1 / p2) | 134.667 / 107.000 / 100.000 |
| Alarmes publicados | 41.667 |
| Pares entre partições causalmente relacionados | 32905.667 |
| Pares entre partições concorrentes | 5647.333 |
| Pares concorrentes com L distinto (proporção) | 95.320% |
| REG sem carimbos (bytes) | 61.370 |
| REG com carimbos (bytes) | 73.477 |

São médias aritméticas, incluindo a proporção. Os bytes correspondem ao payload REG UTF-8, com endereço 127.0.0.1 e porto 7000+k, excluindo HTTP, multipart, base64 e cabeçalhos de rede. O vetor custou em média 7.854 bytes; L, V e os dois separadores acrescentaram 12.107 bytes por REG.

## 10 — Reflexão

**Lamport e concorrência.** Em média, 95.320% dos pares concorrentes tinham L distinto, contra 99.102% sem processador. Isto não demonstra uma relação monotónica com a frequência de alarmes. A ordem total (L,k) também desempata os restantes pares. Uma leitura surgir antes de um alarme nessa vista não prova que o originou. O vetor caracteriza precedência causal, mas também não identifica necessariamente a origem imediata: o processador acumula tudo o que observou.

**Dimensão do vetor.** Com N=3, `0,1,1` ocupa cinco bytes. O custo textual é a soma dos algarismos das componentes mais N−1 vírgulas; aumenta com N e com o valor dos contadores. Mudar N altera a função de partição e a interpretação dos vetores existentes. Não está implementada uma migração para essa situação.

**Relógios e seq.** A componente local repete o valor de seq, mas o protocolo continua a usar seq para ordenar, identificar duplicados, detetar lacunas e pedir intervalos por TCP. As outras componentes do vetor representam o conhecimento dos restantes líderes.

**Entrega causal.** Antes de escrever um registo de p1 com Vmsg[0]=x, a réplica 1 teria de ter aplicado pelo menos x eventos de p0, além de receber o próximo seq de p1. Só conhece o progresso das partições que guarda. Reter registos até satisfazer dependências seria entrega causal, fora do âmbito da ficha.

**Produtor → líder.** Uma leitura perdida não recebeu seq nem gerou um evento. O produtor envia L=0 e V=0 e não observa respostas, pelo que os relógios não detetam essa perda. Seriam necessários mecanismos adicionais de identificação, confirmação ou repetição.
