package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

public class CommandHandler implements AutoCloseable {

    private static final String ADMIN_ROLE_NAME = "admin-arquivo";
    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "config", "replica-token", "replica-remover", "rajada", "comandos-admin"
    );
    private static final Set<String> HELP_COMMANDS = Set.of("comandos-lider", "comandos-admin");

    private final long guildId;
    private final LeaderStore store;
    private final ExecutorService writes;
    private final ExecutorService bursts = Executors.newSingleThreadExecutor();

    // Configuração partilhada por todos os comandos: guarda o servidor onde podem ser executados.
    public CommandHandler(long guildId, LeaderStore store, ExecutorService writes) {
        this.guildId = guildId;
        this.store = store;
        this.writes = writes;
    }

    // Entrada comum a todos os comandos: verifica o servidor e a autorização antes de tratar o pedido.
    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            reply(event, "Este comando só pode ser usado no servidor de teste configurado.", true);
            return;
        }

        if (ADMIN_COMMANDS.contains(event.getName()) && !hasAdminRole(event)) {
            reply(event, "Sem permissão: precisas do papel admin-arquivo para executar este comando.", true);
            return;
        }

        try {
            if ("registar".equals(event.getName())) {
                String text = readText(event, "texto");
                event.deferReply().queue(hook -> writes.execute(() -> register(event, hook, text)));
                return;
            }
            if ("rajada".equals(event.getName())) {
                handleBurst(event);
                return;
            }
            reply(event, buildReply(event), HELP_COMMANDS.contains(event.getName()));
        } catch (IllegalArgumentException exception) {
            reply(event, "Argumentos inválidos: " + exception.getMessage(), true);
        }
    }

    // verifica se o utilizador que executou o comando tem o papel admin-arquivo.
    private boolean hasAdminRole(SlashCommandInteractionEvent event) {
        return event.getMember() != null && event.getMember().getRoles().stream()
                .anyMatch(role -> ADMIN_ROLE_NAME.equals(role.getName()));
    }

    // Comandos provisórios e ajuda; os comandos de escrita são tratados separadamente.
    private String buildReply(SlashCommandInteractionEvent event) {
        return switch (event.getName()) {
            case "estado" -> "/estado recebido: sem argumentos — ainda não implementado";
            case "config" -> handleConfig(event);
            case "recuperar" -> handleRecovery(event);
            case "replica-token" -> "/replica-token recebido: sem argumentos — ainda não implementado";
            case "replica-remover" -> "/replica-remover recebido: id=" + readText(event, "id") + " — ainda não implementado";
            case "comandos-lider" -> buildHelp(false);
            case "comandos-admin" -> buildHelp(true);
            default -> throw new IllegalArgumentException("comando desconhecido. Atualiza a lista no Discord.");
        };
    }

    // Tratamento de /comandos-lider e /comandos-admin: gera a ajuda a partir das definições.
    // includeAdmin indica se também deve apresentar os comandos administrativos.
    private String buildHelp(boolean includeAdmin) {
        StringBuilder leaderHelp = new StringBuilder("**Comandos do líder**\n");
        StringBuilder adminHelp = new StringBuilder("\n**Comandos administrativos — papel admin-arquivo**\n");

        for (var definition : CommandDefinitions.createAll()) {
            if (!(definition instanceof SlashCommandData command)) {
                continue;
            }

            boolean adminOnly = ADMIN_COMMANDS.contains(command.getName());
            if (adminOnly && !includeAdmin) {
                continue;
            }

            StringBuilder section = adminOnly ? adminHelp : leaderHelp;
            if (command.getSubcommands().isEmpty()) {
                appendHelpEntry(section, command.getName(), command.getOptions(), command.getDescription());
            } else {
                for (var subcommand : command.getSubcommands()) {
                    appendHelpEntry(section, command.getName() + " " + subcommand.getName(),
                            subcommand.getOptions(), subcommand.getDescription());
                }
            }
        }

        if (includeAdmin) {
            leaderHelp.append(adminHelp);
        }
        return leaderHelp.toString();
    }

    // Auxiliar de /comandos-lider e /comandos-admin: acrescenta a sintaxe e a descrição de um comando à ajuda.
    private void appendHelpEntry(StringBuilder section, String name, List<OptionData> options, String description) {
        section.append("• `/").append(name);
        for (OptionData option : options) {
            String placeholder = option.isRequired() ? "<" + option.getName() + ">" : "[" + option.getName() + "]";
            section.append(" ").append(option.getName()).append(":").append(placeholder);
        }
        section.append("` — ").append(description).append("\n");
    }

    // Tratamento do comando /config r <n>: valida o subcomando r e confirma o fator de replicação pedido.
    private String handleConfig(SlashCommandInteractionEvent event) {
        if (!"r".equals(event.getSubcommandName())) {
            throw new IllegalArgumentException("usa /config r com o parâmetro n.");
        }
        long replicationFactor = readInteger(event, "n", 1);
        return "/config r recebido: n=" + replicationFactor + " — ainda não implementado";
    }

    // Tratamento do comando /recuperar <de> <ate>: valida os inteiros e a ordem dos extremos do intervalo.
    private String handleRecovery(SlashCommandInteractionEvent event) {
        long from = readInteger(event, "de", 0);
        long to = readInteger(event, "ate", 0);

        if (from > to) {
            throw new IllegalArgumentException("de deve ser menor ou igual a ate.");
        }
        return "/recuperar recebido: de=" + from + ", ate=" + to + " — ainda não implementado";
    }

    private void register(SlashCommandInteractionEvent event, InteractionHook hook, String text) {
        try {
            long lsn = store.registar(event.getUser().getId(), event.getChannel().getId(), text);
            edit(hook, "Registo durável: LSN=" + lsn);
        } catch (IOException exception) {
            writeFailure(hook, exception);
        }
    }

    // A rajada não ocupa a thread de eventos do Discord nem o executor das mensagens normais.
    private void handleBurst(SlashCommandInteractionEvent event) {
        long count = readInteger(event, "n", 1);
        long intervalMs = readInteger(event, "intervaloms", 0);
        String author = event.getUser().getId();
        String channel = event.getChannel().getId();
        event.deferReply().queue(hook -> bursts.execute(() -> {
            long first = 0, last = 0;
            try {
                for (long i = 0; i < count; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    String text = "Rajada " + (i + 1) + "/" + count + " | " + "0123456789abcdef".repeat(16);
                    last = store.registar(author, channel, text);
                    if (i == 0) first = last;
                    if (i + 1 < count && intervalMs > 0) Thread.sleep(intervalMs);
                }
                edit(hook, "Rajada concluída: " + count + " registos; primeiro LSN=" + first + "; último LSN=" + last);
            } catch (IOException exception) {
                writeFailure(hook, exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                edit(hook, "Rajada interrompida; último LSN confirmado=" + last);
            }
        }));
    }

    private void edit(InteractionHook hook, String text) {
        hook.editOriginal(text).setAllowedMentions(List.of()).queue(
                message -> { }, error -> System.err.println("Não foi possível enviar a confirmação do registo."));
    }

    private void writeFailure(InteractionHook hook, IOException exception) {
        System.err.println("Falha na escrita do arquivo: " + exception.getMessage());
        edit(hook, "Não foi possível confirmar a escrita. O registo pode já estar no log; consulta a consola do líder.");
    }

    @Override
    public void close() { bursts.shutdownNow(); }

    // Auxiliar de /registar, /config, /recuperar, /replica-remover e /rajada:
    // lê um argumento obrigatório e verifica se tem o tipo esperado.
    private OptionMapping readOption(SlashCommandInteractionEvent event, String name, OptionType type) {
        OptionMapping option = event.getOption(name);

        if (option == null || option.getType() != type) {
            throw new IllegalArgumentException("o parâmetro " + name + " é obrigatório e deve ser do tipo " + type + ".");
        }
        return option;
    }

    // Auxiliar de /registar e /replica-remover: rejeita texto vazio e preserva o texto original na confirmação.
    private String readText(SlashCommandInteractionEvent event, String name) {
        String value = readOption(event, name, OptionType.STRING).getAsString();

        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " não pode estar vazio.");
        }
        return value;
    }

    // Auxiliar de /config r, /recuperar e /rajada: lê um inteiro e verifica o valor mínimo permitido.
    private long readInteger(SlashCommandInteractionEvent event, String name, long minimum) {
        long value = readOption(event, name, OptionType.INTEGER).getAsLong();

        if (value < minimum) {
            throw new IllegalArgumentException(name + " deve ser maior ou igual a " + minimum + ".");
        }

        return value;
    }

    // Resposta comum a todos os comandos: erros e ajuda ficam visíveis apenas ao autor; o texto ecoado não ativa menções.
    private void reply(SlashCommandInteractionEvent event, String message, boolean ephemeral) {
        var replyAction = event.reply(message.length() <= 2000
                ? message : "Pedido recebido. A confirmação completa segue no ficheiro.")
                .setEphemeral(ephemeral)
                .setAllowedMentions(List.of());

        // Preserva textos longos sem ultrapassar o limite de uma mensagem Discord.
        if (message.length() > 2000) {
            replyAction.addFiles(FileUpload.fromData(message.getBytes(StandardCharsets.UTF_8), "pedido.txt"));
        }

        replyAction.queue(
                hook -> { },
                error -> System.err.println("Não foi possível responder a /" + event.getName() + ".")
        );
    }
}
