# Apoio COMINDIR05

Esta pasta fica fora do repositório. O código da entrega está em `C:\Projects\SDT\SD-2026-2027\Sprints\E1-COMINDIR05`.

- `COMECA-AQUI.md`: instruções curtas para a primeira utilização.
- `COMINDIR05-DO-ZERO.md`: explicação para estudar antes de ler o código.
- `demonstrar.cmd`: painel compacto original. **1** leitura/alarme; **2** falhar líder; **3** isolar líder; **4** atualizar; **5** ficheiros; **0** verificar/sair. As opções 2 e 3 pedem apenas a partição (p0/p1/p2, por omissão p1). O painel escolhe o líder e executa o mesmo cenário de antes, incluindo o reinício ou a reparação. Na opção 3, Enter repara a rede.
- `testar.cmd`: compila, executa os testes de base/recuperação e a matriz completa de ensaios. Demora vários minutos porque mantém os períodos reais de 2 s e os isolamentos de 30/60 s.
- `py -3 -X utf8 integracao.py --unit`: testes sem Kubo.
- `py -3 -X utf8 integracao.py --quick`: testes de base e ensaio de cauda pendente.
- `py -3 -X utf8 mutacoes.py`: mutações controladas, em cópias. Alguns verificadores têm de terminar com FALHOU: nesse contexto a deteção é esperada. Os resultados são guardados em `mutacoes`.

Os CMD mantêm a janela aberta no fim, inclusive se houver um erro. Requerem o JDK e Python já existentes neste computador. O Kubo 0.39.0 está em `runtime/ipfs.exe` e usa uma configuração própria com a API em 16001. O código Java entregue usa 5001 por omissão, mas os scripts passam a API do ambiente separado.

Usa uma sessão de cada vez. Conserva os ficheiros do histórico enquanto a sessão estiver a funcionar. Para terminar a demonstração guiada, escolhe **0**; os membros isolados/terminados são restaurados antes da análise. Fechar à força termina também os processos filhos através de um Job Object do Windows.

O modo com comandos continua disponível através de `py -3 -X utf8 integracao.py --demo`; nesse modo termina-se com `sair`. O duplo clique em `demonstrar.cmd` usa `painel.py`.

As saídas individuais estão em `execucoes/<sessão>/*.log`, os eventos em `eventos-*`, os históricos em `no-*` e os relatórios em `raft-check.txt`/`causal-check.txt`. A matriz usada no relatório da entrega está em `execucoes/20261107-112230`. As cinco mutações estão em `mutacoes/20261107-112838`.

O ensaio completo executou os casos 9(a)-9(e) com processos reais sobre PubSub/TCP. As cinco mutações usaram intercalações determinísticas e chamadas aos métodos reais. Adicionalmente, `mutacoes_rede.py` repetiu sobre PubSub/TCP os casos de candidato atrasado e aplicação de lote obsoleto; os resultados estão em `mutacoes-rede/20261107-114005`. Não são cinco novas matrizes completas sobre a rede. O relatório declara esta distinção.

Se o repositório mudar de pasta, define `COMINDIR05_REPO` com a nova raiz antes de executar. O script compila sempre o código atual dessa raiz; não depende de uma cópia antiga do código Java.

`FICHA.md` conserva a ficha consultada. `preparar.py` e `relatorio.py` foram usados na preparação; não são necessários para executar a demonstração e não devem ser corridos como comandos de arranque.
