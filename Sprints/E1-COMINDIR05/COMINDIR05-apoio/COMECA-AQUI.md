# COMINDIR05 - Painel

Abrir `demonstrar.cmd` e aguardar as eleições.

| Opção | Operação |
|---|---|
| 1 | Leitura S02 29.3 e respetivo alarme, como antes |
| 2 | Escolher partição → falhar líder → eleger substituto → reiniciar antigo |
| 3 | Escolher partição → isolar líder → Enter repara → observar truncagem |
| 4 | Atualizar a tabela |
| 5 | Abrir ficheiros da sessão |
| 0 | Verificar e terminar |

**Partição nas opções 2/3:** p0, p1 ou p2. Enter mantém p1; q cancela.
Exemplo: `2 → p2` executa o cenário de falha em p2.
O painel escolhe automaticamente o líder, como antes. Não é preciso indicar o nó.