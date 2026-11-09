package tpf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

public class CommandHandler {

    private static final String ADMIN_ROLE_NAME = "admin-arquivo";
    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "config", "replica-token", "replica-remover", "rajada", "comandos-admin"
    );
    private static final Set<String> HELP_COMMANDS = Set.of("comandos-lider", "comandos-admin");

    private final long guildId;

    // Guarda o servidor no qual estes comandos podem ser executados.
    public CommandHandler(long guildId) {
        this.guildId = guildId;
    }

    // Verifica o contexto e a autorização antes de interpretar o pedido.
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
            reply(event, buildReply(event), HELP_COMMANDS.contains(event.getName()));
        } catch (IllegalArgumentException exception) {
            reply(event, "Argumentos inválidos: " + exception.getMessage(), true);
        }
    }

    // Verifica os papéis de quem invocou o comando, não os papéis do bot.
    private boolean hasAdminRole(SlashCommandInteractionEvent event) {
        return event.getMember() != null && event.getMember().getRoles().stream()
                .anyMatch(role -> ADMIN_ROLE_NAME.equals(role.getName()));
    }

    // Escolhe o tratamento correspondente ao nome do comando recebido.
    private String buildReply(SlashCommandInteractionEvent event) {
        return switch (event.getName()) {
            case "registar" -> "/registar recebido: texto=" + readText(event, "texto") + " — ainda não implementado";
            case "estado" -> "/estado recebido: sem argumentos — ainda não implementado";
            case "config" -> handleConfig(event);
            case "recuperar" -> handleRecovery(event);
            case "replica-token" -> "/replica-token recebido: sem argumentos — ainda não implementado";
            case "replica-remover" -> "/replica-remover recebido: id=" + readText(event, "id") + " — ainda não implementado";
            case "rajada" -> handleBurst(event);
            case "comandos-lider" -> buildHelp(false);
            case "comandos-admin" -> buildHelp(true);
            default -> throw new IllegalArgumentException("comando desconhecido. Atualiza a lista no Discord.");
        };
    }

    // Gera a ajuda a partir das definições, para acompanhar novos comandos automaticamente.
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

    // Apresenta a sintaxe e uma descrição breve numa linha por comando.
    private void appendHelpEntry(StringBuilder section, String name, List<OptionData> options, String description) {
        section.append("• `/").append(name);
        for (OptionData option : options) {
            String placeholder = option.isRequired() ? "<" + option.getName() + ">" : "[" + option.getName() + "]";
            section.append(" ").append(option.getName()).append(":").append(placeholder);
        }
        section.append("` — ").append(description).append("\n");
    }

    // 'r' é um subcomando de 'config'; 'n' é o seu parâmetro inteiro.
    private String handleConfig(SlashCommandInteractionEvent event) {
        if (!"r".equals(event.getSubcommandName())) {
            throw new IllegalArgumentException("usa /config r com o parâmetro n.");
        }
        long replicationFactor = readInteger(event, "n", 1);
        return "/config r recebido: n=" + replicationFactor + " — ainda não implementado";
    }

    // Além do tipo inteiro, verifica a ordem dos extremos do intervalo.
    private String handleRecovery(SlashCommandInteractionEvent event) {
        long from = readInteger(event, "de", 0);
        long to = readInteger(event, "ate", 0);

        if (from > to) {
            throw new IllegalArgumentException("de deve ser menor ou igual a ate.");
        }
        return "/recuperar recebido: de=" + from + ", ate=" + to + " — ainda não implementado";
    }

    // Confirma os parâmetros; neste sprint não envia uma rajada real.
    private String handleBurst(SlashCommandInteractionEvent event) {
        long count = readInteger(event, "n", 1);
        long intervalMs = readInteger(event, "intervaloms", 0);

        return "/rajada recebido: n=" + count + ", intervaloMs=" + intervalMs + " — ainda não implementado";
    }

    // Centraliza a leitura de argumentos obrigatórios e verifica o tipo.
    private OptionMapping readOption(SlashCommandInteractionEvent event, String name, OptionType type) {
        OptionMapping option = event.getOption(name);

        if (option == null || option.getType() != type) {
            throw new IllegalArgumentException("o parâmetro " + name + " é obrigatório e deve ser do tipo " + type + ".");
        }
        return option;
    }

    // Rejeita texto vazio, preservando o texto original na confirmação.
    private String readText(SlashCommandInteractionEvent event, String name) {
        String value = readOption(event, name, OptionType.STRING).getAsString();

        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " não pode estar vazio.");
        }
        return value;
    }

    // Repete a validação numérica no programa, mesmo existindo limites na interface.
    private long readInteger(SlashCommandInteractionEvent event, String name, long minimum) {
        long value = readOption(event, name, OptionType.INTEGER).getAsLong();

        if (value < minimum) {
            throw new IllegalArgumentException(name + " deve ser maior ou igual a " + minimum + ".");
        }

        return value;
    }

    // Erros e ajuda ficam visíveis apenas ao autor; o texto ecoado não ativa menções.
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
