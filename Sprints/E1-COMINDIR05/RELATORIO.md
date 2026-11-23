# COMINDIR05 - Resultados e reflexão

Ensaios de 07-11-2026, Windows, JDK 21.0.10 com compilação para Java 11, Kubo 0.39.0 com um daemon local isolado. N=3, G=3, nove processos de nós por execução; período de 2000 ms e prazos de 6000-12000 ms, exceto na comparação de prazos fixos. Os processos correram na mesma máquina. Cada ensaio começou em pasta vazia. Os scripts lançaram processos reais e guardaram os eventos, lotes, históricos e entradas; os verificadores foram executados depois de parar os escritores.

## 9(a) - Cauda pendente e regresso do antigo líder

Em p1, o primeiro líder foi n1 no termo 1. Duas leituras foram replicadas; S02 20.4 recebeu seq 3 e ficou pendente. O líder foi terminado antes da publicação. S05 23.9 foi enviada durante a eleição e não chegou a gerar um registo. O substituto recebeu S02 20.6, atribuindo-lhe seq 3 no termo 2. No regresso de n1 foi removido um registo, a partir do seq 3. A leitura S02 20.4 não reaparece com outro seq. As três cópias finais têm três registos iguais.

A indisponibilidade foi 6,578 s, dentro do intervalo previsto ]4 s; 12,2 s], sob os pressupostos da ficha. Houve duas leituras originais perdidas: uma durante a eleição e uma por truncagem. Não se tentou reproduzir os instantes absolutos nem forçar o identificador n2 da ilustração: o resultado eleitoral é determinado pelos prazos.

## 9(b) - Arranque

Dez execuções com os nove nós por ordens baralhadas e cinco com prazo fixo de 6000 ms, iniciados pelo mesmo script sem esperas entre processos.

| Configuração | Grupos observados | Primeiro líder: mínimo / médio / máximo (s) | Termos até ao primeiro líder, média | Termos sem líder |
|---|---|---|---|---|
| Aleatória | 30 | 6.165 / 7.726 / 9.738 | 1.000 | 0 |
| Fixa | 15 | 6.088 / 6.979 / 12.265 | 1.133 | 2 |

No prazo fixo, p0 da execução 3 e p2 da execução 4 dividiram os votos no termo 1: os três nós registaram CANDIDATO e recusaram os outros candidatos por já terem votado em si. Elegeram líder no termo 2. Lançar pelo mesmo script não significa que o Windows execute todos os arranques exatamente no mesmo nanossegundo; diferenças de escalonamento explicam que as restantes eleições fixas tenham terminado no primeiro termo.

O sorteio reduz colisões, não constitui uma garantia de eleição num prazo absoluto. As médias aqui distinguem as duas configurações. Considerando todos os arranques de 9(a)-9(c), os valores agregados são 6,088 / 7,539 / 12,265 s; o máximo pertence ao prazo fixo com nova eleição, não a uma falha de líder.

## 9(c) - Três falhas durante rajadas

Cada execução enviou `rajada 300 10`, terminou o líder de p1 cerca de 0,8 s depois do início da rajada e reiniciou esse nó 30 s depois. O processador esteve ativo. Os resultados incluem apenas perdas inferidas das leituras originais do produtor; os registos truncados também podem conter alarmes.

| Execução | Indisponibilidade (s) | Leituras de p1 no intervalo até ao primeiro lote do substituto | Perdas adicionais associadas à cauda removida | Registos truncados | Leituras originais perdidas, total |
|---|---|---|---|---|---|
| 1 | 8.544 | 76 | 13 | 13 | 89 |
| 2 | 6.156 | 75 | 16 | 17 | 91 |
| 3 | 5.617 | 85 | 15 | 15 | 100 |

Indisponibilidade mínima / média / máxima: 5.617 / 6.772 / 8.544 s. Todos os valores couberam no intervalo previsto. Média de leituras durante a eleição: 78.667; perdas adicionais: 14.667; registos truncados: 15.000. Não se supõe que cada registo truncado seja uma leitura original: na execução 2 houve um alarme entre os 17 removidos.

A rajada dura poucos segundos: não corresponde a um fluxo constante durante os 30 s de falha. O número perdido depende de quantas entradas para p1 caíram efetivamente na janela, além da cauda ainda não publicada. Não é correto multiplicar os 30 s pela taxa nominal de envio e chamar ao resultado a perda medida.

## 9(d) - Partições de rede

O líder n1 de p1 foi isolado durante 30 s. Continuou líder local no termo 1 e aceitou entradas; n2 e n3 elegeram n2 no termo 2. Assim coexistiram duas lideranças autoassumidas em termos diferentes. Ao reparar, n1 demitiu-se e truncou 114 registos a partir do seq 8. Desses registos, 92 correspondiam a leituras originais que não sobreviveram; os restantes eram alarmes. Nenhum dos conteúdos removidos reapareceu com outro seq no histórico final do líder nesta execução.

O tópico IN permaneceu acessível dos dois lados. Como a rajada terminou antes de acabar a eleição, não se deve afirmar que se mediu um fluxo contínuo de escritas simultâneas durante todos os 30 s. O que os logs demonstram é o papel de líder mantido por n1 no termo 1 e a liderança de n2 no termo 2 durante o isolamento. O processador só observou os lotes que atravessaram a comunicação permitida, não os 114 registos locais removidos. O seu histórico de monitorização mostra a passagem para o termo 2 e, depois, para o termo 10.

Depois da convergência, n1, agora seguidor, foi isolado por 60 s sem rajada. Atingiu o termo 9 durante esse isolamento, sem maioria. Ao voltar, iniciou candidatura no termo 10. O líder saudável n2 demitiu-se do termo 2 e n1 foi eleito no termo 10. A perturbação mostra o custo da ausência de Pre-Vote; não foi uma violação de um líder por termo.

## 9(e) - Falta de maioria

Foram terminados o líder n2 e o nó n3 de p1, deixando um seguidor, n1. Durante 30 s enviaram-se 30 leituras S02, uma por segundo. Nenhuma foi guardada: n1 candidatou-se mas não obteve maioria. Depois de voltar um segundo nó, houve nova eleição; o terceiro nó foi também reiniciado antes da comparação final. As três cópias terminaram iguais. A duração desde a primeira falha até ao lote do novo líder foi 40,169 s, incluindo os 30 s deliberados sem quórum; não é comparável diretamente ao intervalo ]4 s; 12,2 s] para uma única falha com dois sobreviventes.

O segundo nó regressou às 1794051070906 ms e o novo líder foi eleito às 1794051080703 ms: 9,797 s após esse arranque, no termo 5. O ensaio escolheu um seguidor como sobrevivente. Se o sobrevivente fosse o líder antigo, sem commit/leases ele poderia continuar a escrever sozinho. Esta distinção é essencial para interpretar disponibilidade nesta implementação.

## 10 - Falhas deliberadas

As mutações foram compiladas separadamente e exercitadas com intercalações determinísticas, preservando o código correto. Usaram os métodos reais de RaftState e PartitionNode, ficheiros e, nos testes de recuperação, TCP local. Não foram novas execuções integrais dos nove processos sobre PubSub para cada mutação: os resultados abaixo distinguem estes testes da matriz 9(a)-9(e).

| Falha | Manifestação controlada | RaftCheck / CausalCheck | Conclusão |
|---|---|---|---|
| Maioria de apenas um voto | Dois estados de candidatos no mesmo termo aceitaram a contagem do seu autovoto como suficiente | RaftCheck: dois líderes no mesmo termo; CausalCheck: sem violação em históricos vazios | A contagem errada quebra a prova de interseção. O teste chamou diretamente onVote com o autovoto; o encaminhamento normal do nó ignora mensagens próprias. |
| Estado só em memória | n2 concedeu a n1, foi recriado sem o voto persistido e concedeu a n3 no mesmo termo; ambos obtiveram maioria | RaftCheck: dois líderes/estado persistido incompatível; CausalCheck: sem violação de dados | O termo e o voto não podem ser esquecidos num reinício. |
| Sem restrição de atualização | Candidato vazio recebeu voto de um nó com dois registos do termo anterior; ao assumir liderança esses históricos foram truncados | RaftCheck regista truncagens; não acusa, por si só, violação eleitoral. CausalCheck termina OK | Os logs não permitem provar que o voto respeitou a restrição. O teste verifica diretamente a decisão errada e a perda de dados. |
| Aplicar lote obsoleto | Foram desativadas a rejeição no RaftState e a revalidação da autoridade antes de aplicar; um seguidor perdeu a sua cauda perante um lote antigo | RaftCheck: cópias finais incompletas; CausalCheck: sem violação no prefixo restante | A proteção deve existir na decisão e continuar válida depois da recuperação fora do lock. |
| L/V a zero na nova liderança | Novo líder com seq anterior tentou criar outro evento usando vetor a zero | A invariante V[k]=seq impediu a escrita; os verificadores não receberam um evento inválido | Um OK posterior não demonstra que a mutação era correta: a guarda de execução bloqueou o erro antes da persistência. |

Testes adicionais da versão correta: 27 verificações de estado/protocolo/monitor, recuo por vários blocos de termo e mudança de termo durante uma recuperação TCP. Esta última foi impedida de aplicar o snapshot da autoridade antiga.

### Complemento: duas mutações sobre PubSub/TCP

Foram ainda executadas duas variantes com nove processos reais:

- **Sem restrição de atualização:** prepararam-se dois históricos com dois registos do termo 1 e um nó atrasado vazio, todos com termo atual 1. O candidato atrasado teve prazo fixo de 6 s e os outros de 12 s para controlar quem iniciava a eleição. Foi eleito no termo 2 e os dois históricos atualizados foram truncados: quatro remoções de cópias de dois eventos. RaftCheck e CausalCheck terminaram OK, confirmando que os carimbos e a unicidade eleitoral, por si só, não provam a correção da decisão de voto. Esta preparação de históricos controla o caso do candidato atrasado; não se confunde com novas leituras medidas do produtor.
- **Aplicação de lote obsoleto:** repetiu-se a rajada e o isolamento do líder por 30 s, desativando a rejeição/revalidação para seguidores. A maioria elegeu outro líder. Depois da reparação foi aceite um lote de termo inferior ao já observado pelo seguidor. No fim os ficheiros convergiram e CausalCheck terminou OK, mas RaftCheck assinalou um lote obsoleto aceite e terminou FALHOU. Houve 110 registos removidos ao longo desta execução defeituosa, oito com conteúdo encontrado noutro seq; não são as medições da versão correta de 9(d).

RaftCheck verifica também os termos dos eventos RECEBIDO contra o termo local já registado. Assim consegue revelar essa aplicação obsoleta mesmo se uma recuperação posterior voltar a igualar os ficheiros.

## 11 - Segurança e coincidência

Em todas as execuções principais: zero termos com mais de um líder, zero decréscimos de termo, zero seq divergentes na comparação final, comprimentos iguais nas três cópias de cada grupo. RaftCheck e CausalCheck terminaram OK. Os resultados das mutações são deliberadamente diferentes e não entram nestes totais.

| Cenário | Maioria aceita escritas? | Minoria aceita escritas? | Destino das escritas minoritárias | Interpretação |
|---|---|---|---|---|
| Falha do líder (c) | Sim, depois da eleição | O nó falhado não responde | Cauda não publicada pode ser truncada | Falta de disponibilidade durante a eleição; eleição segura não equivale a commit seguro |
| Isolamento do líder (d) | Elege novo líder; aceita quando eleito | O líder antigo continua a aceitar IN no termo anterior | Podem desaparecer na truncagem; desapareceram neste ensaio | Não é CP completo: não há commit por maioria; também não oferece disponibilidade universal |
| Isolamento do seguidor (d) | O líder saudável continua | Seguidor sozinho não elege nem atribui seq | Não há novas escritas desse seguidor | Quórum impede liderança minoritária nova; o seu regresso perturba a maioria |
| Falha de dois nós (e) | Não existe maioria até regressar um nó | O seguidor sobrevivente não aceita como líder | As entradas durante a eleição perderam-se | Sacrifício de disponibilidade por falta de quórum eleitoral, com a ressalva do líder sobrevivente descrita acima |

Os termos concretos de p1 foram 1 -> 2 nos ensaios de falha/isolamento do líder e 2 -> 10 após o regresso do seguidor isolado. Os termos de p0 e p2 evoluem independentemente.

## 12 - Reflexão

**Temporização.** O prazo mínimo deve exceder confortavelmente o período de heartbeat e a difusão normal. Se for menor que o período, podem começar eleições entre heartbeats de um líder saudável. Os prazos aleatórios evitaram votos divididos nesta amostra; os fixos produziram dois termos sem líder. Não é uma garantia probabilística universal nem um limite de tempo num sistema assíncrono.

**Termo e maioria.** O voto persistente por termo e a interseção de maiorias impedem dois eleitos no mesmo termo. Não retiram imediatamente a autoridade local de um líder que não recebe notícias da maioria. O termo superior só o demite quando a informação chega.

**CAP.** Particionar os dados em p0/p1/p2 distribui carga; particionar a rede impede coordenação dentro de um grupo. A eleição exige maioria, mas esta ficha não confirma cada escrita por maioria. Um sistema CP completo impediria confirmar escritas no lado minoritário; teria o custo de esperar por réplicas para confirmar e de recusar/protelar pedidos sem maioria. Esse mecanismo foi excluído da ficha.

**Restrição de voto.** Evita escolher um candidato com último registo menos atualizado que o do eleitor. Não protege uma cauda que só existia no líder falhado: os sobreviventes não a conheciam e podem eleger um líder sem ela. Sem commit, escrever no ficheiro local não significa tornar definitivo.

**Persistência.** O termo e o voto têm de sobreviver à falha para não permitir dois votos num termo; o histórico conserva o progresso e os relógios. A incarnação da COMINDIR04 distinguia reinícios do líder fixo; o termo permite agora distinguir épocas eleitorais partilhadas pelo grupo. A substituição atómica dos ficheiros não inclui uma garantia de fsync contra perda de energia.

**Suspeita e Pre-Vote.** Antes, uma falsa suspeita era um aviso. Agora pode alterar o termo, demitir líderes e interromper escritas. O seguidor isolado demonstrou essa perturbação. Pre-Vote averiguaria a possibilidade de eleição antes de aumentar o termo; não foi acrescentado.

**Leituras e duplicados.** As entradas durante ausência de líder são ignoradas, sem retenção/reenvio. Para garantir que nenhuma leitura confirmada se perde seria preciso commit, confirmação e identificação dos pedidos; repetição sem deduplicação pode criar múltiplos registos. Esses mecanismos não cabem nesta ficha. O processador distingue pares seq/termo, por isso uma leitura reaparecida num novo termo pode voltar a produzir alarme.

**Limites da medição.** O protocolo não transporta um identificador único de IN. As perdas e ocorrências com outro seq são inferidas pelo conteúdo e pela multiplicidade; não se consegue individualizar com certeza entradas idênticas. A janela medida acaba no primeiro lote do substituto, pelo que entradas nesse intervalo não são automaticamente todas perdidas. As verificações causais analisam carimbos existentes; não reconstituem dependências apagadas nem implementam entrega causal.
