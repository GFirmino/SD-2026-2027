package tpf;

import java.util.List;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public final class CommandDefinitions {

    // Esta classe apenas fornece definições; não precisa de instâncias.
    private CommandDefinitions() {
    }

    // Lista central: acrescenta aqui o método de cada novo comando.
    public static List<CommandData> createAll() {
        return List.of(
                createRegisterCommand(),
                createStatusCommand(),
                createConfigCommand(),
                createRecoverCommand(),
                createReplicaTokenCommand(),
                createRemoveReplicaCommand(),
                createBurstCommand(),
                createLeaderHelpCommand(),
                createAdminHelpCommand()
        );
    }

    // /registar: recebe o texto que o utilizador pretende guardar.
    private static SlashCommandData createRegisterCommand() {
        return Commands.slash("registar", "Receber um texto para registo (provisório)")
                .addOption(OptionType.STRING, "texto", "Texto a registar", true);
    }

    // /estado: não recebe parâmetros.
    private static SlashCommandData createStatusCommand() {
        return Commands.slash("estado", "Consultar o estado do líder (provisório)");
    }

    // /config r: agrupa a configuração do fator de replicação num subcomando.
    private static SlashCommandData createConfigCommand() {
        var replicationSubcommand = new SubcommandData("r", "Configurar o fator de replicação (provisório)")
                .addOptions(requiredInteger("n", "Valor do fator de replicação", 1));

        return Commands.slash("config", "Configuração administrativa do líder")
                .addSubcommands(replicationSubcommand);
    }

    // /recuperar: declara os dois extremos; a ordem é validada no CommandHandler.
    private static SlashCommandData createRecoverCommand() {
        return Commands.slash("recuperar", "Pedir a recuperação de um intervalo (provisório)")
                .addOptions(
                        requiredInteger("de", "Início do intervalo", 0),
                        requiredInteger("ate", "Fim do intervalo, inclusive", 0)
                );
    }

    // /replica-token: não recebe parâmetros.
    private static SlashCommandData createReplicaTokenCommand() {
        return Commands.slash("replica-token", "Pedir um token de réplica (provisório)");
    }

    // /replica-remover: o identificador da réplica é recebido como texto.
    private static SlashCommandData createRemoveReplicaCommand() {
        return Commands.slash("replica-remover", "Pedir a remoção de uma réplica (provisório)")
                .addOption(OptionType.STRING, "id", "Identificador da réplica", true);
    }

    // /rajada: declara a quantidade de mensagens e o intervalo entre elas.
    private static SlashCommandData createBurstCommand() {
        return Commands.slash("rajada", "Pedir uma rajada de mensagens (provisório)")
                .addOptions(
                        requiredInteger("n", "Quantidade de mensagens", 1),
                        requiredInteger("intervaloms", "Intervalo entre mensagens, em milissegundos", 0)
                );
    }

    // /comandos-lider: ajuda dos comandos disponíveis a todos os membros.
    private static SlashCommandData createLeaderHelpCommand() {
        return Commands.slash("comandos-lider", "Mostrar os comandos normais e uma breve descrição");
    }

    // /comandos-admin: ajuda completa; o CommandHandler verifica o papel exigido.
    private static SlashCommandData createAdminHelpCommand() {
        return Commands.slash("comandos-admin", "Mostrar os comandos normais e administrativos");
    }

    // Um parâmetro inteiro obrigatório; o Discord também aplica este mínimo.
    private static OptionData requiredInteger(String name, String description, long minimum) {
        return new OptionData(OptionType.INTEGER, name, description, true).setMinValue(minimum);
    }
}
