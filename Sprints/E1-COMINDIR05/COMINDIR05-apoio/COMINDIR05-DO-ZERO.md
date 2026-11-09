# COMINDIR05, do zero

Este guia acompanha a implementação da tarefa 8. A explicação anterior da COMINDIR03 continua útil para partições, seq, Lamport e vetores. Aqui a pergunta nova é: **quem passa a atribuir os números se o líder morrer?**

## 1. A história em linguagem simples

Antes havia um líder fixo por partição. As réplicas conseguiam notar silêncio, mas não o substituir. Agora cada partição tem três nós iguais, n1, n2 e n3. Um deles exerce a liderança, e os outros seguem o seu histórico. Cada nó pode assumir os três papéis em momentos diferentes.

Temos três partições de dados e três nós por partição: nove processos de nós. N continua a ser 3, por isso o vetor continua a ter três componentes. Nove processos não significa um vetor de nove posições: os eventos lógicos continuam a ser os registos das três partições.

Os projetos chamam-se `raft04-*` porque a ficha COMINDIR05 pede expressamente esses nomes.

## 2. Seguidor, candidato e líder

- O seguidor espera pelos lotes e guarda cópias.
- Se passar o prazo sem notícia válida do líder, torna-se candidato e pede votos.
- Se conseguir a maioria, torna-se líder e começa imediatamente a publicar lotes.

O papel é memória volátil: depois de reiniciar, o nó começa como seguidor. Não pode assumir que ainda manda só porque mandava antes da falha.

## 3. O que é um termo?

É um número de época eleitoral, local ao grupo da partição. Não é uma hora nem um seq. p0 pode estar no termo 2 e p1 no termo 8; isso não indica uma dependência causal entre eles.

Cada nó conserva o seu maior termo conhecido. Ao receber um termo superior, adota-o e deixa de ser candidato/líder. Ao receber um termo inferior, reconhece uma mensagem obsoleta.

Os termos parecem-se com Lamport por transportarem números crescentes e incorporarem conhecimento recebido. Mas a atualização não é a regra `max+1` por evento: o termo avança numa candidatura ou por adoção de um termo superior. Serve para distinguir lideranças, não para caracterizar causalidade.

## 4. Porque é preciso uma maioria?

Com três nós, maioria é dois. Um candidato vota em si próprio e precisa de mais um voto.

Dois conjuntos de dois membros tirados de três têm sempre um membro em comum. Como cada membro só vota num candidato por termo, não pode apoiar duas maiorias diferentes no mesmo termo. Esta é a prova de segurança da eleição.

Com duas cópias, a maioria seria dois: não tolerava perder nenhuma para eleger. Com três, tolera perder uma. Com quatro, a maioria é três e continua a tolerar apenas uma falha. Não basta contar cópias; é preciso calcular o quórum.

**Não confundir:** no máximo um líder **por termo** não é o mesmo que no máximo um nó a julgar-se líder **em cada instante**.

## 5. Como se vota?

Um pedido contém termo, candidato e informação do seu último registo. O eleitor verifica:

1. O pedido está no seu termo atual, depois de adotar um termo superior se necessário?
2. Ainda não votou nesse termo, ou está a repetir o voto no mesmo candidato?
3. O candidato está pelo menos tão atualizado como ele?

Para a terceira pergunta, compara primeiro o termo do último registo. Só se esses termos forem iguais compara o seq. Um histórico com último termo 4 prevalece sobre outro com último termo 3, mesmo se este for mais comprido.

Repetir um pedido do mesmo candidato não dá um segundo voto novo: a contagem usa um conjunto de eleitores. O pedido pode ter-se repetido por duplicação da comunicação.

## 6. Porque gravar termo e voto antes de responder?

Imagina n2 a votar em n1. Se responder primeiro e falhar antes de guardar, no reinício esquece o voto e pode votar em n3 no mesmo termo. n1 e n3 poderiam obter duas maiorias com a ajuda do mesmo eleitor.

A versão correta grava `termo;votouEm` num temporário e faz uma substituição atómica antes da mensagem. Assim evita um estado parcialmente reescrito numa falha normal do processo. Não força a descarga física com `fsync`: não é uma promessa contra todos os cortes de energia.

Quando se adota um termo novo, o voto volta a zero porque o voto anterior só valia no termo anterior. Dentro do mesmo termo, não se pode limpar o voto só por ter passado algum tempo.

## 7. Porque o prazo é aleatório?

Se os três prazos expirassem juntos, cada nó votava em si e os votos podiam ficar divididos. Na eleição seguinte, prazos iguais podiam repetir o problema.

Por omissão, o heartbeat chega de dois em dois segundos. O prazo de eleição é sorteado entre seis e doze segundos e o teste do prazo acontece a cada 200 ms. Renova-se o sorteio depois de um lote válido, depois de conceder um voto e depois de começar outra candidatura.

Não é garantido que um prazo fixo produza sempre empate numa experiência: diferenças no arranque e no escalonamento podem desempatar. O relatório tem de mostrar o que foi medido, não o que se esperava ver.

## 8. Seguir uma leitura

S02 pertence a p1. O produtor envia uma única mensagem IN no tópico dessa partição. Todos os seus nós a podem receber, mas só o que é líder atribui seq.

O líder entra no monitor do nó, confirma que ainda é líder, calcula seq/L/V, acrescenta o termo ao registo e escreve-o. As entradas recebidas por seguidores são ignoradas; não ficam numa fila para reenviar mais tarde.

No próximo lote, os seguidores recebem uma cópia. O processador pode observar a leitura quente e enviar ALARM-S02 para p2. O novo nome continua a ser passado pela mesma função de partição.

Uma mudança de líder não reinicia seq, L ou V. O nó eleito retoma o seu próprio último registo. O seq recomeçar do último registo local não quer dizer que todos os registos do antigo líder sobreviveram.

## 9. A perda que distingue esta ficha

O líder antigo guardou seq 3, termo 1, mas morreu antes de publicar o lote. Os outros dois só conhecem os registos até ao seq 2. Elegem um líder no termo 2, que recebe outra leitura e lhe atribui seq 3, termo 2.

Ao regressar, o antigo líder tem um seq 3 diferente. Como não houve confirmação por maioria, o seu registo não é definitivo. O histórico do líder atual prevalece e a cauda divergente é truncada.

Por isso a identidade usada pelos nós inclui seq e termo. O processador também distingue `(7,2)` de `(7,3)` e pode produzir alarmes para ambos se os observar e as temperaturas justificarem.

## 10. Como se encontra a divergência?

Um histórico pode ter blocos: seq 1-4 do termo 1, seq 5-7 do termo 2, seq 8-9 do termo 3. O seguidor começa por consultar a cauda a partir do início do seu último bloco. Se o primeiro registo recebido ainda divergir, recua ao bloco anterior. Continua até encontrar um início comum, ou chegar ao primeiro registo.

Depois compara a cauda, conserva o prefixo comum e substitui os registos divergentes pelos do líder. Regista o primeiro seq removido, quantos saíram e os seus termos.

A resposta TCP é obtida fora do monitor para não bloquear a eleição. Antes de a aplicar, o nó confirma novamente se o termo e o líder continuam válidos. Caso contrário, uma recuperação lenta podia trazer de volta um histórico de um líder já substituído.

## 11. Os dois significados de partição

**Partição de dados:** p0, p1 e p2 dividem os sensores e a carga. Cada uma tem eleições independentes.

**Partição de rede:** os nós continuam vivos, mas alguns deixam de conseguir comunicar com os outros.

Criar `isolar-p1-n2` simula a segunda situação. Corta lotes, mensagens de eleição e recuperação TCP de n2 em p1. Não corta as entradas IN: o produtor continua a conseguir alcançar os dois lados.

Se n2 era líder, continua a pensar que é líder do termo antigo. Os outros dois podem eleger um líder num termo novo. Existem então dois líderes autoassumidos, **em termos diferentes**. Depois de reparar a comunicação, o termo superior faz o antigo líder demitir-se e os seus registos divergentes podem desaparecer.

## 12. CAP sem decorar as letras vazias

- C: o serviço comporta-se como uma única cópia consistente.
- A: os pedidos a nós não falhados obtêm respostas bem-sucedidas.
- P: admite-se que a rede pode separar os participantes.

Durante uma partição de rede, não se pode garantir simultaneamente C e A. O RAFT completo só torna uma escrita definitiva depois de a confirmar numa maioria; a minoria não a consegue confirmar.

Esta ficha não faz esse commit. O líder minoritário ainda escreve no ficheiro e esses dados podem desaparecer. **Não deves dizer que isto é um sistema CP completo.** A eleição usa maioria, mas a aceitação de cada escrita não usa.

Também não se deve chamar a isto um serviço AP completo: a minoria sem um líder antigo não consegue eleger um novo, e a aplicação nem tem uma interface de leitura/confirmacão com garantias universais de disponibilidade.

## 13. O seguidor isolado também causa problemas

Um seguidor isolado não recebe heartbeats. Vai aumentando o termo e votando em si, mas sozinho não obtém maioria. Quando regressa com um termo muito alto, faz o líder saudável adotar esse termo e demitir-se. O grupo tem de eleger outra vez.

Não houve duas lideranças no mesmo termo. Houve uma perturbação de disponibilidade. Uma fase de Pre-Vote no RAFT evita aumentar o termo sem primeiro averiguar apoio, mas a ficha exclui essa fase.

Se perdermos dois nós e sobrar um seguidor, esse seguidor também não consegue eleger-se. Se o sobrevivente já fosse líder, sem commit/lease ele poderia continuar a aceitar entradas: é essencial dizer qual foi o nó sobrevivente no ensaio.

## 14. Onde mostrar no código

Na pasta `Sprints/E1-COMINDIR05`:

| Pergunta | Onde procurar |
|---|---|
| Quem escolhe a partição? | `Partitioner.of` |
| Onde fica o termo/voto em disco? | `RaftState.persist`, chamado em `tick`, `adoptTerm` e ao conceder voto |
| Onde se verifica atualização do candidato? | `RaftState.onVoteRequest` |
| Onde se calcula a maioria? | `RaftState.onVote` |
| Onde se adota um termo superior? | `RaftState.adoptTerm` |
| Onde se impede um IN depois da demissão? | `PartitionNode.input`, monitor do nó e `termIfLeader` |
| Onde se recuperam seq/L/V para liderar? | `becomeLeader` e `restore` |
| Onde sai o heartbeat/lote? | `sendBatch` |
| Onde se resolve divergência? | `dataMessage`, `request` e `RecordFile.replace` |
| Onde se impede aplicar uma resposta TCP obsoleta? | Verificação de autoridade ao reentrar no monitor em `dataMessage` |
| Onde se filtram alarmes já tratados? | `AlarmProcessor.onRecord`, conjunto por partição e par seq/termo |
| Quem analisa tudo? | `RaftCheck` e `CausalCheck` |

## 15. Threads e locks, em linguagem simples

O nó tem três receções PubSub, um serviço TCP e temporizadores de eleição/lotes. A comunicação pode chegar ao mesmo tempo que uma eleição ou uma entrada. É por isso que confirmar o papel e atribuir o registo têm de acontecer sob o mesmo monitor.

A ordem de aquisição é nó, depois RaftState, depois ficheiro/log quando necessário. O RaftState nunca volta a tentar adquirir o monitor do nó. Se uma thread adquirisse nó -> RaftState e outra RaftState -> nó, as duas podiam esperar eternamente uma pela outra: deadlock.

O processador tem uma receção por partição; partilha o conhecimento causal e captura cópias coerentes como na COMINDIR03. Um `final VectorClock` não torna o conteúdo imutável: a referência é final, mas o objeto continua mutável.

## 16. O que os verificadores conseguem dizer

RaftCheck relaciona logs de candidaturas/votos/lideranças, estados persistidos, lotes e históricos finais. Conta termos com mais de um líder, decréscimos de termo, tempos e truncagens. Com falhas anotadas, mede até ao primeiro lote de um líder num termo superior.

CausalCheck verifica os históricos locais e usa uma cópia canónica por partição para comparar eventos entre partições. Não compara as três cópias do mesmo evento como se fossem eventos independentes.

Nenhum dos dois sabe reconstruir uma leitura que não esteja em parte alguma. A contagem de perdas usa o conteúdo e a multiplicidade, pois IN não tem um identificador único. Duas leituras iguais podem ser indistinguíveis.

Um OK não transforma esta aplicação em RAFT completo e não prova entrega causal. No ensaio de relógios reiniciados a zero, a invariante pode impedir a escrita antes de haver um registo inválido para analisar; é um resultado diferente de o verificador encontrar um erro num ficheiro.

## 17. Demonstrar sem decorar

Abre `demonstrar.cmd` nesta pasta de apoio. O painel mostra os nove nós numa tabela com papel, termo, seq, L, vetor e rede. **1** envia S02 29.3 e mostra a leitura/alarme. **2** pede a partição, falha o líder, acompanha a eleição e reinicia o nó antigo. **3** pede a partição, isola o líder e mostra a truncagem ao reparar a rede com Enter. Escolhe p0, p1 ou p2; Enter mantém p1. Os restantes passos dos exemplos são automáticos, como no painel original.

O código Java está no repositório, os scripts/testes nesta pasta de apoio e os resultados em subpastas de `execucoes`. **4** atualiza a tabela, **5** abre os ficheiros e **0** verifica e termina. Usa uma sessão de cada vez. O ficheiro `COMECA-AQUI.md` resume os controlos.

Para reproduzir exatamente uma truncagem pendente, segue o ensaio (a) no relatório: é preciso terminar o líder depois de guardar a leitura mas antes de a publicar, não simplesmente matar um nó em qualquer instante.

## 18. Treino para a defesa

Tenta responder antes de consultar a justificação.

**O seq maior ganha a eleição?** Não necessariamente. Primeiro compara-se o termo do último registo; o seq só desempata termos iguais.

**Podem existir dois líderes?** No máximo um eleito por termo, sob as regras corretas e os pressupostos do grupo. Podem existir dois autoassumidos em termos diferentes durante um isolamento.

**O heartbeat faz avançar o vetor?** Não. O evento de dados é a atribuição de seq, não cada mensagem de controlo.

**Ao reiniciar o antigo líder, por que não volta a liderar?** Começa como seguidor e retoma o termo/voto persistidos. Ter uma cauda mais longa não lhe dá automaticamente o papel de líder.

**Pode votar outra vez no mesmo candidato?** Pode repetir a concessão; isso não lhe permite votar noutro candidato nem contar duas vezes na maioria.

**Para que serve um VOTO recusado?** O seu termo pode informar um candidato de que está obsoleto, mesmo sem lhe dar apoio.

**Quanto demora substituir um líder?** Com os valores padrão e ignorando rede/votação, o intervalo previsto é ]4 s; 12,2 s], com média aproximada de 7 s sob os pressupostos indicados na ficha. Não é um limite absoluto num sistema assíncrono; votos divididos e atrasos podem prolongar a eleição.

**Porque não reenviar as leituras perdidas?** A ficha exclui isso. Acrescentar reenvio exigiria confirmação e identificação/deduplicação para não criar eventos repetidos; só reenviar não resolve o problema completo.

**Que garantias faltam para uma escrita aceite não desaparecer?** Commit por maioria e uma definição de confirmação ao produtor, juntamente com identificação adequada dos pedidos. Não basta persistir no líder isolado.

## 19. Um exercício de aplicação

Em p1, n1 tem último registo `(seq=10, termo=2)` e n2 tem `(seq=8, termo=3)`. Quem é mais atualizado para a regra de voto?

Resposta: n2, porque o último termo 3 prevalece sobre 2. Isso não significa que «o seu relógio de Lamport é maior»: termo e Lamport são campos com funções diferentes.

Agora n3 votou em n1 no termo 4, reiniciou e recebe pedido de n2 no termo 4. Pode conceder? Não, se o voto foi corretamente persistido. Pode voltar a conceder a n1 no mesmo termo. Num termo 5 pode conceder outro voto, respeitando a restrição de atualização.

Por fim, isola um seguidor sem o matar. Explica por que aumenta os termos e por que a sua volta pode derrubar temporariamente um líder que funcionava. Se conseguires explicar estes três exemplos sem ler, já tens a base da eleição.

Fonte: ficha COMINDIR05, issue 18 do repositório do docente, e implementação desta tarefa. Os resultados concretos encontram-se no `RELATORIO.md` da implementação; não substituas valores medidos por exemplos deste guia.
