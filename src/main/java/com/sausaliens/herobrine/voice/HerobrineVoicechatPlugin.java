package com.sausaliens.herobrine.voice;

import com.sausaliens.herobrine.HerobrinePlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

import javax.annotation.Nullable;

/**
 * Bridges Herobrine plugin with Simple Voice Chat API.
 * Registers a volume category so players can control Herobrine audio volume independently.
 * Provides the VoicechatServerApi to VoiceManager for playing positional audio.
 */
public class HerobrineVoicechatPlugin implements VoicechatPlugin {

    public static final String HEROBRINE_CATEGORY = "herobrine_voice";
    public static final String PLUGIN_ID = "herobrine";

    private final HerobrinePlugin plugin;

    @Nullable
    private VoicechatServerApi serverApi;

    public HerobrineVoicechatPlugin(HerobrinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        plugin.getLogger().info("[Voice] Herobrine Voice Chat plugin initialized");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();

        // Register volume category so players can adjust Herobrine voice volume
        VolumeCategory category = serverApi.volumeCategoryBuilder()
                .setId(HEROBRINE_CATEGORY)
                .setName("Herobrine")
                .setDescription("Volume of Herobrine's voice and sounds")
                .build();
        serverApi.registerVolumeCategory(category);

        // Pass the API to VoiceManager
        if (plugin.getVoiceManager() != null) {
            plugin.getVoiceManager().setServerApi(serverApi);
        }

        plugin.getLogger().info("[Voice] Voice Chat API ready — category 'herobrine_voice' registered");
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        if (plugin.getVoiceManager() != null) {
            plugin.getVoiceManager().setServerApi(null);
        }
        serverApi = null;
        plugin.getLogger().info("[Voice] Voice Chat API disconnected");
    }

    @Nullable
    public VoicechatServerApi getServerApi() {
        return serverApi;
    }
}
