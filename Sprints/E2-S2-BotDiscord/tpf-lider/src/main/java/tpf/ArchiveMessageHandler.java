package tpf;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class ArchiveMessageHandler {

    private static final String ARCHIVE_CHANNEL_NAME = "arquivo";

    private final long guildId;
    private final LeaderStore store;
    private final ExecutorService writes;

    // Limita a leitura ao servidor de teste configurado no arranque.
    public ArchiveMessageHandler(long guildId, LeaderStore store, ExecutorService writes) {
        this.guildId = guildId;
        this.store = store;
        this.writes = writes;
    }

    // Filtra o evento antes de ler e apresentar o conteúdo da mensagem.
    public void handle(MessageReceivedEvent event) {
        if (!isArchiveMessage(event)) { return; }

        String author = event.getAuthor().getId();
        String channel = event.getChannel().getId();
        String text = event.getMessage().getContentRaw();
        writes.execute(() -> {
            try {
                long lsn = store.registar(author, channel, text);
                System.out.println("[#arquivo] Registo durável: LSN=" + lsn);
            } catch (IOException exception) {
                System.err.println("Não foi possível confirmar a escrita de uma mensagem de #arquivo: " + exception.getMessage());
            }
        });
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

}
