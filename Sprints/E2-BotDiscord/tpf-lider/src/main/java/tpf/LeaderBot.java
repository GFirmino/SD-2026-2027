package tpf;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class LeaderBot extends ListenerAdapter {

    private final long guildId;
    private final CommandHandler commandHandler;

    // Mantém o registo e o tratamento limitados ao servidor de teste.
    private LeaderBot(long guildId) {
        this.guildId = guildId;
        this.commandHandler = new CommandHandler(guildId);
    }

    // Ponto de entrada: carrega a configuração e inicia a ligação.
    public static void main(String[] args) {
        String token;
        long guildId;

        try {
            token = readRequiredEnv("DISCORD_TOKEN");
            guildId = readGuildId();
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage());
            return;
        }

        try {
            startConnection(token, guildId);
        } catch (InvalidTokenException exception) {
            System.err.println("Token inválido. Usa o token da secção Bot do Developer Portal.");
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuração inválida. Verifica o token e os intents do bot.");
        }
    }

    // Lê uma configuração obrigatória sem mostrar o seu valor na consola.
    private static String readRequiredEnv(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta configurar a variável de ambiente " + name + ".");
        }

        return value.strip();
    }

    // O ID identifica o servidor, independentemente do nome que lhe deres.
    private static long readGuildId() {
        String value = readRequiredEnv("DISCORD_GUILD_ID");
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException exception) {
            // A mensagem abaixo também cobre um ID que contenha letras.
        }
        throw new IllegalStateException("DISCORD_GUILD_ID deve conter o ID numérico do servidor.");
    }

    // Configura o JDA e associa esta classe aos eventos enviados pelo Discord.
    private static void startConnection(String token, long guildId) {
        System.out.println("A ligar ao Discord...");

        JDABuilder.createLight(
            token,
            GatewayIntent.GUILD_MESSAGES, // Eventos de mensagens nos servidores.
            GatewayIntent.MESSAGE_CONTENT // Conteúdo das mensagens; ativar também no Portal.
        )
        .addEventListeners(new LeaderBot(guildId))
        .build();

        // A ligação é assíncrona: a confirmação chega através de onReady.
    }

    // O JDA chama este método quando a sessão está pronta a receber eventos.
    @Override
    public void onReady(ReadyEvent event) {
        var jda = event.getJDA();

        System.out.println("Bot online: " + jda.getSelfUser().getName());
        System.out.println("Servidores disponíveis: " + jda.getGuilds().size());

        var guild = jda.getGuildById(guildId);
        if (guild == null) {
            System.err.println("Servidor não encontrado. Verifica DISCORD_GUILD_ID e o convite do bot.");
            return;
        }

        registerCommands(guild);
    }

    // Envia as definições para o Discord uma vez, quando o bot fica pronto.
    private void registerCommands(Guild guild) {
        guild.updateCommands()
            .addCommands(CommandDefinitions.createAll())
            .queue(
                commands -> System.out.println(
                        commands.size() + " comandos registados em " + guild.getName() + "."
                ),
                error -> System.err.println("Não foi possível registar os comandos. Verifica o convite com " + "bot e applications.commands. Erro: " + error.getClass().getSimpleName())
            );
    }

    // Encaminha os pedidos para a classe responsável pelos comandos.
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        commandHandler.handle(event);
    }

    // Explica falhas de autenticação ou intents quando a sessão termina.
    @Override
    public void onShutdown(ShutdownEvent event) {
        switch (event.getCode()) {
            case 4004 -> System.err.println("O Discord recusou o token. Verifica DISCORD_TOKEN.");
            case 4014 -> System.err.println("O Discord recusou um intent. Ativa Message Content Intent na secção Bot do Portal.");
            default -> System.out.println("Ligação ao Discord encerrada. Código: " + event.getCode());
        }
    }
}
