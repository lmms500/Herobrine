package com.sausaliens.herobrine.managers;

import com.sausaliens.herobrine.HerobrinePlugin;
import com.sausaliens.herobrine.voice.HerobrineVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages Herobrine's voice audio system via Simple Voice Chat API.
 *
 * Audio types:
 * - WHISPER: Static audio played directly to a player (inside their head)
 * - POSITIONAL: Locational audio from Herobrine's position (3D spatial)
 * - AMBIENT: Distant/quiet positional audio for atmosphere
 *
 * Audio files are loaded from plugins/Herobrine/sounds/ folder.
 * Format: 48kHz 16-bit signed mono PCM (little-endian) stored as .pcm files.
 *
 * Sounds are categorized by folder:
 * - sounds/whisper/   — creepy whispers (played directly to player)
 * - sounds/breathing/ — breathing sounds (positional near Herobrine)
 * - sounds/ambient/   — ambient horror sounds (positional, farther away)
 * - sounds/laugh/     — distorted laughter (intense phase only)
 * - sounds/voice/     — actual "speech" clips (climax phase)
 */
public class VoiceManager {
    private final HerobrinePlugin plugin;
    private final Logger logger;
    private final Random random;

    @Nullable
    private VoicechatServerApi serverApi;

    // Audio clip cache: category -> list of PCM audio samples
    private final Map<SoundCategory, List<short[]>> audioCache = new HashMap<>();

    // Active audio players for cleanup
    private final List<AudioPlayer> activePlayers = Collections.synchronizedList(new ArrayList<>());

    // Cooldown per player to avoid spamming sounds
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();
    private static final long VOICE_COOLDOWN_MS = 15000L; // 15 seconds between voice sounds

    public enum SoundCategory {
        WHISPER("whisper"),
        BREATHING("breathing"),
        AMBIENT("ambient"),
        LAUGH("laugh"),
        VOICE("voice");

        private final String folder;

        SoundCategory(String folder) {
            this.folder = folder;
        }

        public String getFolder() {
            return folder;
        }
    }

    public VoiceManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.random = new Random();

        // Initialize cache
        for (SoundCategory cat : SoundCategory.values()) {
            audioCache.put(cat, new ArrayList<>());
        }

        loadSounds();
    }

    public void setServerApi(@Nullable VoicechatServerApi api) {
        this.serverApi = api;
        if (api != null) {
            logger.info("[Voice] VoiceManager connected to Voice Chat API — " + getTotalSoundCount() + " sounds loaded");
        }
    }

    public boolean isAvailable() {
        return serverApi != null;
    }

    // ===================== SOUND LOADING =====================

    private void loadSounds() {
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        if (!soundsDir.exists()) {
            soundsDir.mkdirs();
            // Create category subdirectories
            for (SoundCategory cat : SoundCategory.values()) {
                new File(soundsDir, cat.getFolder()).mkdirs();
            }
            logger.info("[Voice] Created sounds/ directory structure. Add .wav files to enable voice audio.");
            return;
        }

        for (SoundCategory cat : SoundCategory.values()) {
            File catDir = new File(soundsDir, cat.getFolder());
            if (!catDir.exists()) {
                catDir.mkdirs();
                continue;
            }

            File[] files = catDir.listFiles((dir, name) ->
                    name.endsWith(".wav") || name.endsWith(".pcm"));
            if (files == null) continue;

            for (File file : files) {
                try {
                    short[] samples;
                    if (file.getName().endsWith(".wav")) {
                        samples = loadWavFile(file);
                    } else {
                        samples = loadPcmFile(file);
                    }
                    if (samples != null && samples.length > 0) {
                        audioCache.get(cat).add(samples);
                    }
                } catch (Exception e) {
                    logger.warning("[Voice] Failed to load " + file.getName() + ": " + e.getMessage());
                }
            }

            int count = audioCache.get(cat).size();
            if (count > 0) {
                logger.info("[Voice] Loaded " + count + " sound(s) for category: " + cat.getFolder());
            }
        }
    }

    /**
     * Load a WAV file and extract PCM samples.
     * Expects: mono or stereo, 16-bit signed, any sample rate (will be resampled to 48kHz).
     */
    @Nullable
    private short[] loadWavFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[44];
            if (fis.read(header) < 44) {
                logger.warning("[Voice] WAV file too small: " + file.getName());
                return null;
            }

            // Parse WAV header
            // Bytes 0-3: "RIFF"
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                logger.warning("[Voice] Not a valid WAV file: " + file.getName());
                return null;
            }

            int channels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            int sampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8) |
                    ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
            int bitsPerSample = (header[34] & 0xFF) | ((header[35] & 0xFF) << 8);

            if (bitsPerSample != 16) {
                logger.warning("[Voice] Only 16-bit WAV supported, got " + bitsPerSample + "-bit: " + file.getName());
                return null;
            }

            // Read all data after header
            byte[] data = readAllBytes(fis);

            // Convert to short[]
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int totalSamples = data.length / 2;
            short[] rawSamples = new short[totalSamples];
            buffer.asShortBuffer().get(rawSamples);

            // If stereo, convert to mono
            short[] monoSamples;
            if (channels == 2) {
                monoSamples = new short[totalSamples / 2];
                for (int i = 0; i < monoSamples.length; i++) {
                    monoSamples[i] = (short) ((rawSamples[i * 2] + rawSamples[i * 2 + 1]) / 2);
                }
            } else {
                monoSamples = rawSamples;
            }

            // Resample to 48kHz if needed
            if (sampleRate != 48000) {
                monoSamples = resample(monoSamples, sampleRate, 48000);
            }

            return monoSamples;
        }
    }

    /**
     * Load a raw PCM file (48kHz 16-bit signed mono little-endian).
     */
    @Nullable
    private short[] loadPcmFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = readAllBytes(fis);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            short[] samples = new short[data.length / 2];
            buffer.asShortBuffer().get(samples);
            return samples;
        }
    }

    /**
     * Simple linear interpolation resampling.
     */
    private short[] resample(short[] input, int fromRate, int toRate) {
        double ratio = (double) fromRate / toRate;
        int newLength = (int) (input.length / ratio);
        short[] output = new short[newLength];

        for (int i = 0; i < newLength; i++) {
            double srcPos = i * ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;

            if (srcIndex + 1 < input.length) {
                output[i] = (short) (input[srcIndex] * (1.0 - frac) + input[srcIndex + 1] * frac);
            } else if (srcIndex < input.length) {
                output[i] = input[srcIndex];
            }
        }

        return output;
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = is.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    // ===================== PLAYBACK =====================

    /**
     * Play a whisper sound directly to a player (non-positional, "inside their head").
     * Used during SUBTLE/ACTIVE pacing phases.
     */
    public boolean playWhisper(Player player) {
        return playStatic(player, SoundCategory.WHISPER, 0.7f);
    }

    /**
     * Play breathing sounds at a specific location (positional, 3D).
     * The sound appears to come FROM that location.
     */
    public boolean playBreathing(Player player, Location location) {
        return playLocational(player, location, SoundCategory.BREATHING, 1.0f, 16.0f);
    }

    /**
     * Play ambient horror sound at a location (positional, large range).
     */
    public boolean playAmbient(Player player, Location location) {
        return playLocational(player, location, SoundCategory.AMBIENT, 0.5f, 32.0f);
    }

    /**
     * Play distorted laughter at Herobrine's location (intense phase).
     */
    public boolean playLaugh(Player player, Location location) {
        return playLocational(player, location, SoundCategory.LAUGH, 1.0f, 24.0f);
    }

    /**
     * Play a voice clip directly to a player (climax phase, "Herobrine speaks").
     */
    public boolean playVoice(Player player) {
        return playStatic(player, SoundCategory.VOICE, 1.0f);
    }

    /**
     * Play a random sound from a category directly to a player (static/non-positional).
     */
    private boolean playStatic(Player player, SoundCategory category, float volume) {
        if (!canPlay(player, category)) return false;

        short[] samples = getRandomSample(category);
        if (samples == null) return false;

        VoicechatConnection connection = serverApi.getConnectionOf(player.getUniqueId());
        if (connection == null) return false;

        UUID channelId = UUID.randomUUID();
        StaticAudioChannel channel = serverApi.createStaticAudioChannel(
                channelId,
                serverApi.fromServerLevel(player.getWorld()),
                connection
        );
        if (channel == null) return false;

        channel.setCategory(HerobrineVoicechatPlugin.HEROBRINE_CATEGORY);

        // Apply volume
        short[] adjusted = applyVolume(samples, volume);

        AudioPlayer audioPlayer = serverApi.createAudioPlayer(channel, serverApi.createEncoder(), adjusted);
        audioPlayer.setOnStopped(() -> activePlayers.remove(audioPlayer));
        activePlayers.add(audioPlayer);
        audioPlayer.startPlaying();

        setCooldown(player);

        if (plugin.getConfigManager().isDebugMode()) {
            logger.info("[Voice] Playing " + category.getFolder() + " (static) to " + player.getName());
        }

        return true;
    }

    /**
     * Play a random sound from a category at a location (locational/3D positional).
     */
    private boolean playLocational(Player player, Location location, SoundCategory category, float volume, float distance) {
        if (!canPlay(player, category)) return false;

        short[] samples = getRandomSample(category);
        if (samples == null) return false;

        VoicechatConnection connection = serverApi.getConnectionOf(player.getUniqueId());
        if (connection == null) return false;

        UUID channelId = UUID.randomUUID();
        Position pos = serverApi.createPosition(location.getX(), location.getY(), location.getZ());

        LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                channelId,
                serverApi.fromServerLevel(location.getWorld()),
                pos
        );
        if (channel == null) return false;

        channel.setCategory(HerobrineVoicechatPlugin.HEROBRINE_CATEGORY);
        channel.setDistance(distance);

        // Apply volume
        short[] adjusted = applyVolume(samples, volume);

        AudioPlayer audioPlayer = serverApi.createAudioPlayer(channel, serverApi.createEncoder(), adjusted);
        audioPlayer.setOnStopped(() -> activePlayers.remove(audioPlayer));
        activePlayers.add(audioPlayer);
        audioPlayer.startPlaying();

        setCooldown(player);

        if (plugin.getConfigManager().isDebugMode()) {
            logger.info("[Voice] Playing " + category.getFolder() + " (locational at "
                    + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                    + ", dist=" + distance + ") to " + player.getName());
        }

        return true;
    }

    // ===================== HELPERS =====================

    private boolean canPlay(Player player, SoundCategory category) {
        if (serverApi == null) return false;
        if (!plugin.getConfigManager().isEnabled()) return false;
        if (!plugin.getConfigManager().isVoiceEnabled()) return false;

        List<short[]> sounds = audioCache.get(category);
        if (sounds == null || sounds.isEmpty()) return false;

        // Check cooldown (whispers and ambient don't share cooldown)
        if (category != SoundCategory.AMBIENT) {
            Long lastTime = playerCooldowns.get(player.getUniqueId());
            if (lastTime != null && (System.currentTimeMillis() - lastTime) < VOICE_COOLDOWN_MS) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    private short[] getRandomSample(SoundCategory category) {
        List<short[]> sounds = audioCache.get(category);
        if (sounds == null || sounds.isEmpty()) return null;
        return sounds.get(random.nextInt(sounds.size()));
    }

    private short[] applyVolume(short[] samples, float volume) {
        float configVolume = plugin.getConfigManager().getVoiceVolume();
        float finalVolume = volume * configVolume;

        if (finalVolume >= 0.99f && finalVolume <= 1.01f) {
            return samples; // No adjustment needed
        }

        short[] adjusted = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            adjusted[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                    (int) (samples[i] * finalVolume)));
        }
        return adjusted;
    }

    private void setCooldown(Player player) {
        playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public int getTotalSoundCount() {
        int total = 0;
        for (List<short[]> sounds : audioCache.values()) {
            total += sounds.size();
        }
        return total;
    }

    public int getSoundCount(SoundCategory category) {
        List<short[]> sounds = audioCache.get(category);
        return sounds != null ? sounds.size() : 0;
    }

    public void reloadSounds() {
        stopAll();
        for (SoundCategory cat : SoundCategory.values()) {
            audioCache.get(cat).clear();
        }
        loadSounds();
    }

    public void stopAll() {
        synchronized (activePlayers) {
            for (AudioPlayer player : activePlayers) {
                try {
                    player.stopPlaying();
                } catch (Exception ignored) {}
            }
            activePlayers.clear();
        }
    }

    public void cleanup() {
        stopAll();
        playerCooldowns.clear();
        for (SoundCategory cat : SoundCategory.values()) {
            audioCache.get(cat).clear();
        }
    }
}
