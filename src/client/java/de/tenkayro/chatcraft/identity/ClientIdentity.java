package de.tenkayro.chatcraft.identity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;
import de.tenkayro.chatcraft.mixin.PlayerEntityAccessor;
import de.tenkayro.chatcraft.mixin.PlayerListEntryAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class ClientIdentity {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ActivityLog activity;
    private final AtomicLong skinRequest = new AtomicLong();
    private volatile GameProfile skinProfile;

    public ClientIdentity(ActivityLog activity) {
        this.activity = activity;
    }

    public String nickname() {
        return ChatCraftConfig.get().clientNickname;
    }

    public boolean setNickname(String nickname) {
        if (!isValidName(nickname)) return false;
        ChatCraftConfig config = ChatCraftConfig.get();
        config.clientNickname = nickname;
        config.save();
        refreshLocalProfile();
        activity.info("Identity", "Client-side nickname set to " + nickname);
        return true;
    }

    public void clearNickname() {
        ChatCraftConfig config = ChatCraftConfig.get();
        config.clientNickname = "";
        config.save();
        refreshLocalProfile();
        activity.info("Identity", "Client-side nickname cleared");
    }

    public void loadConfiguredSkin() {
        String username = ChatCraftConfig.get().clientSkinUsername;
        if (!username.isBlank()) loadSkin(username, false);
    }

    public boolean setSkin(String username) {
        if (!isValidName(username)) return false;
        loadSkin(username, true);
        return true;
    }

    public void clearSkin() {
        skinRequest.incrementAndGet();
        skinProfile = null;
        ChatCraftConfig config = ChatCraftConfig.get();
        config.clientSkinUsername = "";
        config.save();
        refreshLocalProfile();
        activity.info("Identity", "Client-side skin override cleared");
    }

    public boolean isLocalProfile(GameProfile profile) {
        MinecraftClient client = MinecraftClient.getInstance();
        return profile != null && client.getSession().getUuidOrNull() != null
                && client.getSession().getUuidOrNull().equals(profile.getId());
    }

    public Text rewriteLocalName(Text message) {
        String nickname = nickname();
        String accountName = MinecraftClient.getInstance().getSession().getUsername();
        if (nickname.isBlank() || accountName.isBlank() || nickname.equals(accountName)
                || !message.getString().contains(accountName)) return message;
        return rewriteText(message, accountName, nickname);
    }

    private static MutableText rewriteText(Text source, String accountName, String nickname) {
        TextContent content = source.getContent();
        MutableText rewritten;
        if (content instanceof PlainTextContent.Literal literal) {
            rewritten = Text.literal(literal.string().replace(accountName, nickname));
        } else if (content instanceof TranslatableTextContent translatable) {
            Object[] arguments = translatable.getArgs().clone();
            for (int index = 0; index < arguments.length; index++) {
                if (arguments[index] instanceof Text text) {
                    arguments[index] = rewriteText(text, accountName, nickname);
                } else if (arguments[index] instanceof String text) {
                    arguments[index] = text.replace(accountName, nickname);
                }
            }
            rewritten = MutableText.of(new TranslatableTextContent(
                    translatable.getKey(), translatable.getFallback(), arguments));
        } else {
            rewritten = source.copyContentOnly();
        }
        rewritten.setStyle(source.getStyle());
        for (Text sibling : source.getSiblings()) {
            rewritten.append(rewriteText(sibling, accountName, nickname));
        }
        return rewritten;
    }

    private void loadSkin(String username, boolean persistOnSuccess) {
        long request = skinRequest.incrementAndGet();
        activity.info("Identity", "Loading client-side skin for " + username);
        HttpRequest lookup = HttpRequest.newBuilder(
                        URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "ChatCraft/2.0")
                .GET()
                .build();

        HTTP.sendAsync(lookup, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseProfile(response, username))
                .thenApply(ClientIdentity::fillProfileTextures)
                .thenCompose(profile -> MinecraftClient.getInstance().getSkinProvider().fetchSkinTextures(profile)
                        .thenApply(textures -> new LoadedSkin(profile, textures)))
                .whenComplete((loaded, error) -> MinecraftClient.getInstance().execute(() -> {
                    if (request != skinRequest.get()) return;
                    if (error != null || loaded == null || loaded.textures == null) {
                        Throwable cause = unwrap(error);
                        activity.error("Identity", "Could not load skin for " + username, cause);
                        notifyPlayer("ChatCraft: could not load skin for " + username + " ("
                                + safeMessage(cause) + ")");
                        return;
                    }
                    skinProfile = loaded.profile;
                    if (persistOnSuccess) {
                        ChatCraftConfig config = ChatCraftConfig.get();
                        config.clientSkinUsername = username;
                        config.save();
                    }
                    refreshLocalProfile();
                    activity.info("Identity", "Client-side skin loaded from " + username);
                    notifyPlayer("ChatCraft: skin set from " + username);
                }));
    }

    public void refreshLocalProfile() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::refreshLocalProfile);
            return;
        }
        if (client.player == null || client.getNetworkHandler() == null) return;

        GameProfile accountProfile = client.getGameProfile();
        String effectiveName = nickname().isBlank() ? accountProfile.getName() : nickname();
        GameProfile effectiveProfile = new GameProfile(accountProfile.getId(), effectiveName);
        effectiveProfile.getProperties().putAll(accountProfile.getProperties());
        GameProfile selectedSkin = skinProfile;
        if (selectedSkin != null) {
            effectiveProfile.getProperties().removeAll("textures");
            effectiveProfile.getProperties().putAll(
                    "textures", selectedSkin.getProperties().get("textures"));
        }

        ((PlayerEntityAccessor) client.player).chatcraft$setGameProfile(effectiveProfile);
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        if (entry != null) {
            PlayerListEntryAccessor accessor = (PlayerListEntryAccessor) entry;
            accessor.chatcraft$setProfile(effectiveProfile);
            accessor.chatcraft$setTexturesSupplier(
                    client.getSkinProvider().getSkinTexturesSupplier(effectiveProfile));
        }
    }

    private static GameProfile parseProfile(HttpResponse<String> response, String requestedName) {
        if (response.statusCode() != 200) {
            throw new CompletionException(new IllegalStateException(response.statusCode() == 404
                    ? "Minecraft profile not found" : "Mojang profile API returned HTTP " + response.statusCode()));
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String id = json.has("id") ? json.get("id").getAsString() : "";
        String name = json.has("name") ? json.get("name").getAsString() : requestedName;
        if (!id.matches("[0-9a-fA-F]{32}")) {
            throw new CompletionException(new IllegalStateException("Mojang returned an invalid profile UUID"));
        }
        String uuid = id.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5");
        return new GameProfile(UUID.fromString(uuid), name);
    }

    private static GameProfile fillProfileTextures(GameProfile profile) {
        ProfileResult result = MinecraftClient.getInstance().getSessionService().fetchProfile(profile.getId(), true);
        if (result == null || result.profile() == null
                || result.profile().getProperties().get("textures").isEmpty()) {
            throw new CompletionException(new IllegalStateException("Minecraft profile has no usable skin data"));
        }
        return result.profile();
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) return new IllegalStateException("Unknown skin loading error");
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) return "unknown error";
        return error.getMessage();
    }

    private static void notifyPlayer(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal(message), false);
    }

    public static boolean isValidName(String value) {
        return value != null && VALID_NAME.matcher(value).matches();
    }

    private record LoadedSkin(GameProfile profile, SkinTextures textures) { }
}
