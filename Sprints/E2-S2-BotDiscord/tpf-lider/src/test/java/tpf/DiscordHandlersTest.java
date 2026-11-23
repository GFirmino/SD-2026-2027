package tpf;

import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordHandlersTest {
    @TempDir Path directory;
    private final ExecutorService writes = Executors.newSingleThreadExecutor();

    private SlashCommandInteractionEvent command(String name, boolean admin) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild().getIdLong()).thenReturn(123L);
        when(event.getUser().getId()).thenReturn("456");
        when(event.getChannel().getId()).thenReturn("789");
        when(event.getName()).thenReturn(name);
        Role role = mock(Role.class); when(role.getName()).thenReturn("admin-arquivo");
        when(event.getMember().getRoles()).thenReturn(admin ? List.of(role) : List.of());
        return event;
    }

    private void option(SlashCommandInteractionEvent event, String name, OptionType type, Object value) {
        OptionMapping option = mock(OptionMapping.class);
        when(option.getType()).thenReturn(type);
        if (type == OptionType.INTEGER) when(option.getAsLong()).thenReturn((Long) value);
        else when(option.getAsString()).thenReturn((String) value);
        when(event.getOption(name)).thenReturn(option);
    }

    @SuppressWarnings("unchecked")
    private void deferred(SlashCommandInteractionEvent event, Consumer<String> onEdit) {
        ReplyCallbackAction action = mock(ReplyCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<?> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenAnswer(invocation -> {
            onEdit.accept(invocation.getArgument(0)); return edit;
        });
        when(event.deferReply()).thenReturn(action);
        doAnswer(invocation -> {
            Consumer<InteractionHook> callback = invocation.getArgument(0); callback.accept(hook); return null;
        }).when(action).queue(any(Consumer.class));
    }

    @Test void slashReplyIsIssuedOnlyWhenItsDurableLineExists() throws Exception {
        try (LeaderStore store = new LeaderStore(directory); CommandHandler handler = new CommandHandler(123L, store, writes)) {
            SlashCommandInteractionEvent event = command("registar", false);
            option(event, "texto", OptionType.STRING, "texto de teste");
            CountDownLatch done = new CountDownLatch(1); AtomicBoolean persisted = new AtomicBoolean();
            deferred(event, message -> {
                try {
                    List<String> lines = Files.readAllLines(directory.resolve("registos.log"));
                    persisted.set(message.contains("LSN=1") && lines.size() == 1
                            && Registo.fromLine(lines.get(0)).texto().equals("texto de teste"));
                } catch (Exception ignored) { persisted.set(false); }
                finally { done.countDown(); }
            });
            handler.handle(event);
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(persisted.get());
        } finally { writes.shutdownNow(); }
    }

    @Test void unauthorizedBurstAndProvisionalCommandsDoNotWrite() throws Exception {
        try (LeaderStore store = new LeaderStore(directory); CommandHandler handler = new CommandHandler(123L, store, writes)) {
            SlashCommandInteractionEvent denied = command("rajada", false); handler.handle(denied);
            verify(denied).reply(contains("Sem permissão")); verify(denied, never()).deferReply();
            SlashCommandInteractionEvent status = command("estado", false); handler.handle(status);
            verify(status).reply(contains("ainda não implementado"));
            assertTrue(Files.readString(directory.resolve("registos.log")).isEmpty());
        } finally { writes.shutdownNow(); }
    }

    @Test void adminBurstProducesFiveHundredDurableRecords() throws Exception {
        try (LeaderStore store = new LeaderStore(directory, 65536, 4096, 60000);
             CommandHandler handler = new CommandHandler(123L, store, writes)) {
            SlashCommandInteractionEvent event = command("rajada", true);
            option(event, "n", OptionType.INTEGER, 500L); option(event, "intervaloms", OptionType.INTEGER, 0L);
            CountDownLatch done = new CountDownLatch(1); AtomicBoolean success = new AtomicBoolean();
            deferred(event, message -> { success.set(message.contains("500 registos")); done.countDown(); });
            handler.handle(event);
            assertTrue(done.await(20, TimeUnit.SECONDS)); assertTrue(success.get());
            assertEquals(500, Files.readAllLines(directory.resolve("registos.log")).size());
            try (var files = Files.list(directory.resolve("selados"))) {
                assertTrue(files.filter(p -> p.toString().endsWith(".bin")).count() >= 2);
            }
        } finally { writes.shutdownNow(); }
    }

    private MessageReceivedEvent archiveMessage() {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class, RETURNS_DEEP_STUBS);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild().getIdLong()).thenReturn(123L);
        when(event.isFromType(ChannelType.TEXT)).thenReturn(true);
        when(event.getChannel().getName()).thenReturn("arquivo");
        when(event.getAuthor().getId()).thenReturn("456");
        when(event.getChannel().getId()).thenReturn("789");
        when(event.getMessage().getContentRaw()).thenReturn("mensagem; com\nlinhas");
        return event;
    }

    @Test void archiveUsesSameStoreAndIgnoresBotsOtherChannelsAndGuilds() throws Exception {
        try (LeaderStore store = new LeaderStore(directory)) {
            ArchiveMessageHandler handler = new ArchiveMessageHandler(123L, store, writes);
            MessageReceivedEvent human = archiveMessage(); handler.handle(human);
            MessageReceivedEvent bot = archiveMessage(); when(bot.getAuthor().isBot()).thenReturn(true); handler.handle(bot);
            MessageReceivedEvent webhook = archiveMessage(); when(webhook.isWebhookMessage()).thenReturn(true); handler.handle(webhook);
            MessageReceivedEvent other = archiveMessage(); when(other.getChannel().getName()).thenReturn("geral"); handler.handle(other);
            MessageReceivedEvent guild = archiveMessage(); when(guild.getGuild().getIdLong()).thenReturn(999L); handler.handle(guild);
            MessageReceivedEvent dm = archiveMessage(); when(dm.isFromGuild()).thenReturn(false); handler.handle(dm);
            MessageReceivedEvent thread = archiveMessage(); when(thread.isFromType(ChannelType.TEXT)).thenReturn(false); handler.handle(thread);
            writes.shutdown(); assertTrue(writes.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, store.registar("456", "789", "entrada pelo mesmo caminho"));
            List<String> lines = Files.readAllLines(directory.resolve("registos.log"));
            assertEquals(2, lines.size());
            assertEquals("mensagem; com\nlinhas", Registo.fromLine(lines.get(0)).texto());
        } finally { writes.shutdownNow(); }
    }
}
