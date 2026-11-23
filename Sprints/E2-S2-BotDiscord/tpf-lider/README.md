# tpf-lider - Sprint E2-02

Java 17+, Maven e JDA. Implementa a escrita local pedida na [ficha E2-02](https://github.com/cunhaestgv/SD-2026-2027/issues/21): registo durável, chunks com padding, HKDF-SHA256 e AES-256-GCM. Não implementa réplicas, colocação, catálogo, manifesto ou recuperação a partir de réplicas.

## Configuração e arranque

1. Abrir o `pom.xml` deste projeto no IntelliJ e carregar as dependências Maven.
2. Copiar `config/LeaderBot.run.xml.example` para `.run/LeaderBot Config.run.xml`.
3. Preencher na cópia local `DISCORD_TOKEN` e `DISCORD_GUILD_ID`. O segundo é o ID do servidor, não o ID da aplicação ou do bot.
4. Usar **LeaderBot Config**, com `tpf.LeaderBot` e a pasta de trabalho na raiz deste projeto.

O bot deve estar instalado com os scopes `bot` e `applications.commands`. Ativar **Message Content Intent** no Developer Portal. Os intents são `GUILD_MESSAGES` e `MESSAGE_CONTENT`. Apenas o servidor configurado é tratado; os comandos são registados nesse servidor, não globalmente.

Em alternativa, definir as duas variáveis no ambiente e executar `tpf.LeaderBot` com as dependências no classpath. Java não carrega `.env` automaticamente. Os testes locais não precisam de credenciais.

No Git fica apenas o XML de exemplo com placeholders. A configuração real em `.run/`, os ficheiros do IDE, a chave e os dados locais estão excluídos pelo `.gitignore`.

## Parâmetros da JVM

| Propriedade | Valor por omissão | Significado |
|---|---|---|
| `tpf.chunkMaxBytes` | 65536 | Limite do corpo binário, incluindo o cabeçalho de 12 bytes, antes do padding |
| `tpf.padBytes` | 4096 | Alinhamento do conteúdo antes da cifra |
| `tpf.selagemMs` | 5000 | Prazo desde o primeiro registo do chunk |
| `tpf.dataDir` | `dados-lider` | Pasta dos dados locais, relativa à pasta de trabalho |

Exemplo: `-Dtpf.chunkMaxBytes=65536 -Dtpf.padBytes=4096 -Dtpf.selagemMs=5000`. Os três limites têm de ser positivos. Estas propriedades não implementam o comando `/config`, que permanece provisório.

## Entradas e comandos

| Entrada | Comportamento |
|---|---|
| `/registar texto:Olá` | Grava e confirma o LSN atribuído |
| Mensagem de uma pessoa em `#arquivo` | Usa o mesmo método de escrita; mostra o LSN na consola |
| `/rajada n:500 intervaloms:0` | Gera 500 registos sintéticos; exige `admin-arquivo` |
| `/estado`, `/config r`, `/recuperar`, `/replica-token`, `/replica-remover` | Respostas provisórias e validação da Sprint 1 |
| `/comandos-lider`, `/comandos-admin` | Ajuda; a segunda exige `admin-arquivo` |

`#arquivo` é o canal de texto com esse nome exato no servidor configurado. Ignoram-se bots, webhooks, mensagens privadas, threads e outros canais. Só se guarda texto, incluindo texto vazio de mensagens sem conteúdo textual; anexos ficam fora deste sprint. Guardam-se os IDs do autor e do canal, independentes de mudanças de nome.

Os comandos administrativos exigem o papel, mesmo que o utilizador tenha outras permissões de administrador. A rajada usa `LeaderStore.registar`, com um índice e 256 caracteres de carga por texto para que 500 registos ultrapassem o limite de tamanho. O intervalo é aplicado entre registos. Não publica 500 mensagens no Discord.

Os comandos de escrita fazem `deferReply` para reconhecer a interação dentro do prazo do Discord. Isso não confirma um LSN. A resposta final com LSN só é emitida depois de `registar` devolver, após `FileChannel.force(true)`. A rajada corre num executor próprio; as mensagens normais não esperam pelos seus intervalos.

## Escrita e concorrência

`LeaderBot` cria uma única instância de `LeaderStore`, partilhada pelos dois handlers. O método sincronizado `registar(autor, canal, texto)` atribui o LSN, escreve a linha completa, faz `force(true)`, atualiza o LSN em memória, regista o evento e acrescenta o registo ao chunk aberto.

O temporizador usa o mesmo monitor. É agendado no primeiro registo, não é adiado pelos seguintes e identifica o chunk a que pertence; um callback antigo não pode selar um chunk novo. O registo que faz atingir ou ultrapassar o limite fica nesse chunk, que é imediatamente selado. O próximo abre um novo chunk. Um chunk selado rejeita alterações e `Sealed` conserva cópias defensivas dos seus dados.

Uma falha depois da escrita durável pode impedir a confirmação ao Discord. Não se apaga a linha nem se reutiliza o LSN. Depois de uma falha de escrita/selagem, a instância recusa novas escritas até reiniciar. Não há reenvio automático ou garantia de exatamente uma vez perante uma repetição manual.

A lock local `lider.lock` impede duas instâncias de escrever na mesma pasta. Não é eleição de líder nem coordenação distribuída.

## Ficheiros locais

Dentro de `dados-lider/`:

```text
master.key
lider.lock
registos.log
eventos-lider.txt
selados/<chunkId>.bin
selados/<chunkId>.hdr
```

`master.key` contém 32 bytes de `SecureRandom`. O ficheiro é criado com acesso privado e fica com leitura de dados apenas para o dono: modo 0400 em POSIX ou ACL do dono em Windows. Em Windows mantêm-se direitos do dono para gerir a ACL e remover o ficheiro. A chave e as DEK não são incluídas no `.hdr`. Uma chave existente com outro tamanho impede o arranque; se houver chunks e faltar a chave, o arranque também é recusado.

`registos.log` usa UTF-8 e uma linha por registo:

```text
lsn;instante;autor;canal;textoBase64
```

`instante` é um long em milissegundos Unix; autor e canal são IDs Discord. Só o texto é Base64, preservando quebras de linha, ponto e vírgula e Unicode. **Base64 não é cifra**: o WAL local permite ler o texto. A cifra pedida neste sprint protege os chunks em `.bin`.

`eventos-lider.txt` usa `instante;evento;detalhe`:

```text
<ms>;REGISTO;lsn=1
<ms>;SELADO;chunkId=<sha256>;de=1;ate=200;tamanho=<bytes-bin>;paddingBytes=<bytes-antes-da-cifra>
```

O intervalo é inclusivo. Os temporários de selagem terminam em `.tmp`; são escritos com `force(true)` e movidos atomicamente. O `.hdr` é publicado antes do `.bin`, de modo que um binário visível já tenha o seu cabeçalho no destino. Não se introduz um manifesto.

## Formato binário do chunk

Inteiros em **big-endian**, como `DataOutputStream`:

| Campo | Tamanho |
|---|---|
| Magic `0x54504631` (`TPF1`) | 4 bytes |
| Número de registos | 4 bytes |
| Comprimento útil total, incluindo estes 12 bytes | 4 bytes |
| Registos por ordem de LSN | Variável |
| Padding de zeros | Até ao próximo múltiplo de `PAD_BYTES` |

Cada registo contém `lsn` e `instante` como dois longs de 8 bytes, seguidos das strings `autor`, `canal` e `texto`. Cada string tem um comprimento de 4 bytes e os respetivos bytes UTF-8. Não se usa `writeUTF`, que tem outro formato e limite. `Chunk.read` valida os comprimentos e a sequência, reconstrói os registos e ignora o padding depois do comprimento útil.

## HKDF, AES-GCM e .hdr

Cada chunk usa salt de 32 bytes e nonce de 12 bytes novos:

```text
PRK = HMAC-SHA256(chave=salt, dados=master.key)
DEK = HMAC-SHA256(chave=PRK, dados=UTF8("tpf-chunk-v1") || 0x01)
```

São 32 bytes de DEK, o primeiro bloco de expansão HKDF. A transformação é `AES/GCM/NoPadding`, chave de 256 bits e tag de 128 bits. O AAD é fornecido antes da cifra:

```text
UTF8("tpf-v1") || int32-big-endian(1) || salt[32] || nonce[12]
```

O `.bin` contém o ciphertext e a tag. O seu nome é o SHA-256 desses bytes em hexadecimal minúsculo. O `.hdr` correspondente tem uma linha UTF-8:

```text
1;<salt em Base64 padrão>;<nonce em Base64 padrão>
```

A versão é o inteiro decimal 1; os dois arrays são Base64. O `.hdr` partilha o ID do `.bin`, não é nomeado pelo hash do próprio cabeçalho. Salt e nonce são públicos; não permitem derivar a DEK sem a MK.

`ChunkCrypto.open` verifica por ordem: hash contra ID, versão, autenticação GCM. Alterar o ciphertext e renomear o ficheiro com o novo hash também falha na autenticação. Outra chave, salt ou nonce igualmente impedem a abertura.

**Tamanho e tag:** o conteúdo antes da cifra é múltiplo de `PAD_BYTES`. O `.bin` tem mais 16 bytes da tag: 4096 -> 4112, por exemplo. A verificação é `(tamanhoBin - 16) % PAD_BYTES == 0`. Segue-se a ordem explícita da ficha, padding antes de GCM. Exigir simultaneamente que plaintext e ciphertext com tag sejam múltiplos de 4096 seria incompatível com esse formato. Não são acrescentados bytes não autenticados para disfarçar a diferença.

## Reinício e fecho

O arranque valida `registos.log` e continua no último LSN mais um. Uma cauda sem newline é truncada até à última linha completa; linhas completas inválidas ou LSN repetidos/em falta impedem o arranque, sem os saltar silenciosamente.

Para conservar os registos duráveis ainda não selados, o líder abre os `.bin` locais, verifica-os contra o log e repõe apenas o que ainda não está coberto. Não reescreve chunks existentes. É reposição local do WAL, não `/recuperar`, catálogo ou recuperação por réplicas. Chave errada ou chunk local corrompido impedem este arranque.

O fecho normal interrompe a rajada, termina as escritas pendentes e sela o chunk aberto. Num fim abrupto, os registos confirmados continuam no WAL e são repostos no arranque seguinte.

## Compilar e testar

```text
mvn test
mvn package
```

JUnit e Mockito são dependências apenas de teste; a cifra usa o JDK. Os testes usam pastas temporárias e eventos JDA simulados, com armazenamento real, sem token nem mensagens externas.

Cobertura: vetor HKDF do RFC 5869, round-trip, formato binário, alterações de ciphertext em memória/no disco, salt/nonce/versão/chave, hash/padding, imutabilidade, confirmação após escrita, reinício normal/abrupto, cauda parcial/corrupção, concorrência, tamanho/prazo real de 5 s, 500 registos, permissões da chave, exclusão de duas instâncias, filtros do arquivo e autorização da rajada.

Referências: [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869), [Cipher no JDK 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html) e [FileChannel.force](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)).
