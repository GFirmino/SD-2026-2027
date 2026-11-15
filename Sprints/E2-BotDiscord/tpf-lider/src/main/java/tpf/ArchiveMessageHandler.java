package tpf;

import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class ArchiveMessageHandler {

    private static final String ARCHIVE_CHANNEL_NAME = "arquivo";

    private final long guildId;

    // Limita a leitura ao servidor de teste configurado no arranque.
    public ArchiveMessageHandler(long guildId) {
        this.guildId = guildId;
    }

    // Filtra o evento antes de ler e apresentar o conteúdo da mensagem.
    public void handle(MessageReceivedEvent event) {
        if (!isArchiveMessage(event)) { return; }

        logReceivedMessage(event);
    }

    // Aceita pessoas no canal de texto #arquivo; ignora bots, webhooks e outros canais.
    private boolean isArchiveMessage(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            return false;
        }

        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return false;
        }

        return event.isFromType(ChannelType.TEXT) && ARCHIVE_CHANNEL_NAME.equals(event.getChannel().getName());
    }

    // Confirma a receção na consola; ainda não guarda, cifra ou replica o texto.
    private void logReceivedMessage(MessageReceivedEvent event) {
        String authorName = event.getAuthor().getName();
        String content = event.getMessage().getContentRaw();

        if (content.isEmpty()) {
            content = "[mensagem sem texto]";
        }

        System.out.printf("[#arquivo] Mensagem de %s: %s — processamento ainda não implementado.%n", authorName, content);
    }
}
