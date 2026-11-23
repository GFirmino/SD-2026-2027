# Validação da implementação COMINDIR04

Ambiente: Windows, Kubo 0.43.1, JDK 26.0.2.1, compilação `--release 11`. Todos os participantes executados na mesma máquina. Não foi feito commit ou push desta implementação.

## Verificações executadas

| Verificação | Resultado |
|---|---|
| Compilação dos quatro projetos | OK |
| Lotes inválidos: quantidade, sequência, limites do cabeçalho | Rejeitados nos testes |
| Snapshot de lote independente da lista pendentes | OK |
| Heartbeat duplicado/antigo não retira suspeita | OK |
| Salto de hb, retoma e reinício | OK nos testes do monitor |
| Publicação e subscrição através de Kubo real | OK |
| Alarme S02 → ALARM-S02 através de dois líderes | OK |
| Terminar líder após append e antes do lote | OK |
| Recuperação desse registo nas duas réplicas via ultimoSeq vazio | OK |
| Suspeita pelos quatro participantes que acompanham p0 | OK |
| Suspensão e retoma da mesma incarnação | Produziram falsa suspeita |
| Rajada com intervalo de 10 ms | OK |
| CausalCheck nos dois ensaios integrados | Zero violações |
| PartitionCheck nos dois ensaios integrados | Seis cópias iguais aos respetivos líderes |
| Lançador com entradas e encerramento | OK |
| Controlador: reiniciar, pausar, retomar e falhar | OK |

Os testes de integração falham se as seis cópias não forem iguais, não apenas quando o processo verificador termina com um código diferente de zero.

## Ensaios realizados

Foram executados ensaios de integração com períodos de 1 s e 5 s. O de 5 s incluiu 300 leituras, suspensão de p2 durante 20 s e um minuto ocioso. Os resultados resumidos abaixo foram conservados; os ficheiros auxiliares e logs históricos foram removidos desta pasta na simplificação da estrutura.

O comando atual `testar.cmd` volta a gerar os logs e relatórios na pasta temporária indicada no ecrã. A validação anterior combina cenários e não equivale à matriz completa e isolada da ficha.

## Resultados do ensaio integrado de 5 s

| Métrica | Medição |
|---|---|
| Lotes publicados p0 / p1 / p2 | 24 / 26 / 26 |
| Mensagens LOTE / REG equivalentes transportados | 76 / 337 |
| Maior lote observado | 126 registos |
| Intervalo médio p1 | 4999,840 ms |
| Maior intervalo p2, incluindo suspensão | 25015 ms |
| Latência média de receção p0 / p1 / p2 | 2513,980 / 2826,745 / 2194,725 ms |
| Latência máxima observada | 5013 ms |
| Tempo de deteção p0: mínimo / médio / máximo | 15,093 / 15,318 / 15,503 s |
| Suspeitas de falha p0 | 4 observações, uma por participante |
| Falsas suspeitas de p2 | 4 observações da mesma pausa |
| Pedidos de recuperação | 2, nas duas réplicas de p0 |
| Eventos HB_PERDIDO | 0 |
| Bytes por registo em LOTE, incluindo tráfego vazio | 62,754 |
| Bytes por REG equivalente observado | 71,543 |

Os quatro tempos de deteção cabem em ]10 s,16 s]. A falha ocorreu pouco depois de um heartbeat, pelo que ficaram próximos do extremo superior. O maior intervalo de p2 inclui a suspensão; os envios em atraso após a retoma contribuem para a média continuar perto de 5 s.

Os bytes são apenas do protocolo da aplicação. Os 337 registos transportados não incluem o registo de p0 que ficou pendente, foi perdido no envio e depois recuperado por TCP. Os ficheiros dos líderes e das réplicas incluem esse registo adicional. A proporção elevada de lotes vazios inclui o minuto ocioso e as esperas dos cenários; não deve ser apresentada como proporção isolada da rajada.

A latência máxima ligeiramente superior a Δ inclui o transporte e a receção, além da espera no líder. O lote acrescenta até Δ de espera ideal; isso não limita toda a latência observada a exatamente Δ.

## Problemas encontrados e resolvidos

O `java.exe` disponível inicialmente no PATH era o lançador Oracle `javapath`. Terminar esse processo não terminava a JVM filha; o primeiro ensaio não provocava uma falha real. Os scripts passaram a resolver `java.home` e a arrancar o executável real do JDK. O ensaio falhado não integra os resultados apresentados.

A API do Kubo rejeitava a identificação de navegador usada por omissão pelo PowerShell. A verificação do lançador passou a usar `User-Agent: COMINDIR04` e `Content-Type: application/octet-stream`, sem alterar permissões da API.

## Ensaios formais ainda não executados

Esta validação não executou as três repetições de falha de **p1 durante uma rajada**, com reinício 30 s depois; a comparação completa de sessões isoladas com Δ=1000,5000,10000 ms; nem as cinco mutações deliberadas da ficha. Os lançadores, parâmetros e verificadores necessários estão disponíveis. Não se apresentam previsões como resultados medidos.

| Mutação pedida | Efeito a investigar — previsão, não medição |
|---|---|
| Não publicar lote vazio | Suspeitas quando não há entradas; perda do anúncio de progresso ocioso |
| Incrementar L/V em cada heartbeat | Invariante V[k]=seq pode falhar no próximo evento; o código correto também a verifica no líder |
| Ler ultimoSeq fora do bloqueio | Recuperação prematura dependente da intercalação das threads; pode não ocorrer num ensaio |
| ultimoSeq=0 e descartar 1/10 dos lotes | Perda final pode ficar invisível ao mecanismo de recuperação |
| Fator=1 | Menor margem para atrasos; maior risco de falsas suspeitas |

Um verificador só observa os dados que lhe chegam. A ausência de uma violação no relatório não prova que todas as leituras foram aceites, nem que uma condição de corrida nunca possa ocorrer. A explicação e defesa das decisões continuam a fazer parte da avaliação da ficha.
