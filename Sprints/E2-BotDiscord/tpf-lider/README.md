# tpf-lider

Bot em Java 17+ e JDA. Regista nove comandos no servidor de teste: sete operações
com validação e resposta provisória e dois comandos de ajuda. Ainda não executa operações de arquivo,
não gera tokens de réplica e não produz rajadas reais. A deteção de mensagens
normais em `#arquivo` ainda está por implementar.

## Configuração e arranque no IntelliJ

1. Abre este projeto Maven e faz **Reload All Maven Projects**.
2. Em **Run → Edit Configurations**, escolhe a configuração de `tpf.LeaderBot`.
3. Em **Environment variables**, configura:
   - `DISCORD_TOKEN`: token da secção **Bot** do Developer Portal.
   - `DISCORD_GUILD_ID`: ID do servidor de teste (não é o ID da aplicação nem do canal).
4. Para copiar o ID, ativa **Definições do utilizador → Avançado → Modo de programador**
   no Discord e usa **Copiar ID do servidor** no menu de contexto do servidor.
5. Mantém **Store as project file** desmarcado e executa `LeaderBot`.

O bot deve estar instalado nesse servidor com os scopes `bot` e
`applications.commands`. Ativa **Message Content Intent** no Developer Portal.
As permissões base são **View Channels** e **Send Messages**.

O arranque deve mostrar `Bot online: ...` e, depois, `9 comandos registados em ...`.
Cada arranque sincroniza a lista completa de comandos desta aplicação nesse
servidor; não cria comandos globais. Mantém apenas uma instância do programa em execução.

O token nunca deve ser escrito no código ou enviado para o Git. `.idea/`, `.env`,
`token.txt` e `master.key` estão excluídos. Java não carrega um `.env` automaticamente.

## Comandos e exemplos

| Comando | Exemplo no Discord | Quem pode usar |
| --- | --- | --- |
| `/registar` | `/registar texto:Olá` | Membros do servidor |
| `/estado` | `/estado` | Membros do servidor |
| `/config r` | `/config r n:3` | Papel `admin-arquivo` |
| `/recuperar` | `/recuperar de:10 ate:20` | Membros do servidor |
| `/replica-token` | `/replica-token` | Papel `admin-arquivo` |
| `/replica-remover` | `/replica-remover id:replica-1` | Papel `admin-arquivo` |
| `/rajada` | `/rajada n:5 intervaloms:100` | Papel `admin-arquivo` |
| `/comandos-lider` | `/comandos-lider` | Membros do servidor |
| `/comandos-admin` | `/comandos-admin` | Papel `admin-arquivo` |

`/comandos-lider` descreve os comandos normais. `/comandos-admin` mostra a mesma
ajuda e acrescenta os comandos administrativos, incluindo a sintaxe e as opções.
As duas respostas de ajuda são privadas, visíveis apenas a quem executa o comando.
A ajuda usa as descrições de `CommandDefinitions`, pelo que novos comandos
aparecem automaticamente na secção correspondente a `ADMIN_COMMANDS`.

Em `/config r`, `r` é um subcomando e `n` é uma opção. O nome `intervaloms`
aparece em minúsculas porque o Discord exige nomes de opções em minúsculas.
Os comandos administrativos podem aparecer na lista de todos, mas o programa
recusa a execução por membros sem o papel exigido, mesmo que sejam administradores
do servidor. É o papel do autor do pedido que conta.

Regras numéricas usadas nesta base: `n >= 1`, `intervaloms >= 0` e
`0 <= de <= ate`. Assim, intervalo de zero milissegundos é aceite. O ID de réplica
é texto não vazio; não se verifica a existência de réplicas neste sprint.

## Organização do código

Classes, métodos, variáveis e constantes usam nomes em inglês. Comandos,
mensagens e comentários mantêm-se em português.

- `LeaderBot`: configuração, ligação, publicação dos comandos e encaminhamento dos eventos.
- `CommandDefinitions`: definições dos comandos, com um método por comando e uma lista em `createAll()`.
- `CommandHandler`: permissões, validação e respostas provisórias.

`CommandDefinitions` descreve os nomes, as opções, os tipos e os limites aceites
pelo Discord. Não envia pedidos nem executa comandos. O método
`LeaderBot.registerCommands()` publica essas definições no servidor.

## Adicionar um comando

1. Em `CommandDefinitions`, cria um método `create...Command()` com o nome,
   a descrição e as opções do novo comando.
2. Acrescenta a chamada desse método à lista devolvida por `createAll()`.
3. Em `CommandHandler.buildReply()`, acrescenta o tratamento do novo nome.
   Se for administrativo, inclui-o também em `ADMIN_COMMANDS`.
4. Reinicia o bot para sincronizar a definição com o servidor de teste.

## Verificação manual no Discord

- Executa os sete exemplos e confirma que as respostas ecoam os argumentos.
- Experimenta `/recuperar de:20 ate:10`: o programa deve responder com um erro privado.
- Experimenta letras num parâmetro inteiro ou valores abaixo do mínimo:
  o próprio Discord deve impedir o envio.
- Usa uma conta sem `admin-arquivo` para confirmar que os quatro comandos
  administrativos originais e `/comandos-admin` são recusados. Os comandos normais
  e `/comandos-lider` devem continuar disponíveis.
- Confirma que `/comandos-lider` não lista comandos administrativos, mesmo quando
  executado por um administrador, e que `/comandos-admin` inclui ambas as categorias.
- Textos compostos apenas por espaços são recusados. Confirmações que ultrapassem
  2000 caracteres são enviadas num ficheiro de texto para preservar o conteúdo.

Para compilar num terminal que tenha Maven e JDK 17+ disponíveis: `mvn compile`.
