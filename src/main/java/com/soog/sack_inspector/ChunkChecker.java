package com.soog.sack_inspector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.concurrent.ConcurrentHashMap;
import me.lucko.fabric.api.permissions.v0.Permissions;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ChunkChecker {
    private static final String CONFIG_FILE = "config/chunk_checker.json";
    private static final String MOD_ID = "sack_inspector";
    private static Map<String, ChunkData> savedChunks = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean debugMode = false;
    private static final Map<UUID, ChunkPos> playerLastChunks = new HashMap<>();
    private static final BlockWatcher blockWatcher = new BlockWatcher();
    private static List<String> watchedItems = new ArrayList<>();
    private static final UUID ADMIN_UUID = UUID.fromString("05269d91-9917-42dc-8c0a-72994a5c9a03");

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
        loadConfig();
        registerResourceReloader();
        setupChunkDebug();
        blockWatcher.init(savedChunks, watchedItems);
    }

    public static void reload() {
        loadConfig();
        registerResourceReloader();
        blockWatcher.init(savedChunks, watchedItems);
    }

    private static void registerResourceReloader() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.of(MOD_ID, "itemlist_loader");
                    }

                    @Override
                    public void reload(ResourceManager manager) {
                        try {
                            var resource = manager.getResource(Identifier.of(MOD_ID, "itemlist.txt"));

                            if (resource.isPresent()) {
                                try (InputStream stream = resource.get().getInputStream();
                                     BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {

                                    watchedItems = reader.lines()
                                            .map(String::trim)
                                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                                            .collect(Collectors.toList());

                                    System.out.println("[SackInspector] Loaded " + watchedItems.size() + " items from itemlist.txt");
                                    blockWatcher.init(savedChunks, watchedItems);
                                }
                            } else {
                                System.err.println("[SackInspector] Could not find itemlist.txt in resources!");
                            }
                        } catch (Exception e) {
                            System.err.println("[SackInspector] Error loading itemlist.txt:");
                            e.printStackTrace();
                        }
                    }
                }
        );
    }

    private static void setupChunkDebug() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (!debugMode) return;

            server.getPlayerManager().getPlayerList().forEach(player -> {
                int currentChunkX = Math.floorDiv((int) player.getX(), 16);
                int currentChunkZ = Math.floorDiv((int) player.getZ(), 16);
                ChunkPos currentChunk = new ChunkPos(currentChunkX, currentChunkZ);

                ChunkPos lastChunk = playerLastChunks.get(player.getUuid());
                if (lastChunk == null || !lastChunk.equals(currentChunk)) {
                    playerLastChunks.put(player.getUuid(), currentChunk);

                    for (Map.Entry<String, ChunkData> entry : savedChunks.entrySet()) {
                        if (entry.getValue().x == currentChunkX && entry.getValue().z == currentChunkZ) {
                            String blockInfo = entry.getValue().blockType != null ?
                                    String.format(" (Block: %s)", entry.getValue().blockType) : "";
                            player.sendMessage(Text.literal(
                                    String.format("§a[DEBUG] Entered saved chunk '%s' at X: %d, Z: %d%s",
                                            entry.getKey(), currentChunkX, currentChunkZ, blockInfo)
                            ));
                        }
                    }
                }
            });
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("si")
                        .then(CommandManager.literal("reload")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .executes(context -> {
                                    reload();
                                    context.getSource().sendMessage(Text.literal("§aSackInspector configuration reloaded!"));
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("activate")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .executes(context -> {
                                    blockWatcher.toggleActive();
                                    context.getSource().sendMessage(Text.literal(
                                            blockWatcher.isActive() ?
                                                    "§aSackInspector notifications enabled" :
                                                    "§cSackInspector notifications disabled"
                                    ));
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("checkperm")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player != null) {
                                        // Direct permission check
                                        boolean permCheck = Permissions.check(player, "sackinspector.caninspectsacks", false);

                                        // Debug output
                                        context.getSource().sendMessage(Text.literal("§e[SackInspector] Permission check for " + player.getName().getString() + ":"));
                                        context.getSource().sendMessage(Text.literal("§e[SackInspector] - Permission Node: sackinspector.caninspectsacks"));
                                        context.getSource().sendMessage(Text.literal("§e[SackInspector] - Has Permission: " + permCheck));
                                        context.getSource().sendMessage(Text.literal("§e[SackInspector] - Op Level: " + player.getServer().getPermissionLevel(player.getGameProfile())));
                                    }
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("debug")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .executes(context -> {
                                    debugMode = !debugMode;
                                    context.getSource().sendMessage(Text.literal(
                                            debugMode ? "Debug mode enabled - chunk entry detection active"
                                                    : "Debug mode disabled"
                                    ));
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("setchunk")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            String chunkName = StringArgumentType.getString(context, "name");

                                            if (player != null) {
                                                int blockX = (int) player.getX();
                                                int blockZ = (int) player.getZ();

                                                int chunkX = Math.floorDiv(blockX, 16);
                                                int chunkZ = Math.floorDiv(blockZ, 16);

                                                savedChunks.put(chunkName, new ChunkData(chunkX, chunkZ));
                                                saveConfig();

                                                String message = String.format(
                                                        "Chunk '%s' set at chunk coordinates X: %d, Z: %d (block coordinates X: %d, Z: %d)",
                                                        chunkName, chunkX, chunkZ, blockX, blockZ);
                                                context.getSource().sendMessage(Text.literal(message));
                                            }
                                            return 1;
                                        })
                                )
                        )
                        .then(CommandManager.literal("setblock")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .then(CommandManager.argument("block", StringArgumentType.word())
                                        .then(CommandManager.argument("chunkname", StringArgumentType.word())
                                                .executes(context -> {
                                                    String rawBlock = StringArgumentType.getString(context, "block");
                                                    String blockId = "minecraft:" + rawBlock;
                                                    String chunkName = StringArgumentType.getString(context, "chunkname");

                                                    Identifier blockIdentifier = Identifier.of(blockId);
                                                    if (!Registries.BLOCK.containsId(blockIdentifier)) {
                                                        context.getSource().sendMessage(Text.literal("§cInvalid block type: " + rawBlock));
                                                        return 0;
                                                    }

                                                    ChunkData chunk = savedChunks.get(chunkName);
                                                    if (chunk == null) {
                                                        context.getSource().sendMessage(Text.literal("§cChunk not found: " + chunkName));
                                                        return 0;
                                                    }

                                                    String oldBlock = chunk.blockType;
                                                    chunk.setBlockType(blockId);
                                                    saveConfig();

                                                    if (oldBlock != null) {
                                                        context.getSource().sendMessage(Text.literal(
                                                                String.format("§aChanged block type for chunk '%s' from %s to %s",
                                                                        chunkName, oldBlock, blockId)
                                                        ));
                                                    } else {
                                                        context.getSource().sendMessage(Text.literal(
                                                                String.format("§aSet block type for chunk '%s' to %s",
                                                                        chunkName, blockId)
                                                        ));
                                                    }
                                                    return 1;
                                                })
                                        ))
                        )
                        .then(CommandManager.literal("unsetblock")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .then(CommandManager.argument("chunkname", StringArgumentType.word())
                                        .executes(context -> {
                                            String chunkName = StringArgumentType.getString(context, "chunkname");
                                            ChunkData chunk = savedChunks.get(chunkName);

                                            if (chunk == null) {
                                                context.getSource().sendMessage(Text.literal("§cChunk not found: " + chunkName));
                                                return 0;
                                            }

                                            if (chunk.blockType == null) {
                                                context.getSource().sendMessage(Text.literal("§cNo block type set for chunk: " + chunkName));
                                                return 0;
                                            }

                                            String oldBlock = chunk.blockType;
                                            chunk.setBlockType(null);
                                            saveConfig();

                                            context.getSource().sendMessage(Text.literal(
                                                    String.format("§aRemoved block type %s from chunk '%s'",
                                                            oldBlock, chunkName)
                                            ));
                                            return 1;
                                        })
                                )
                        )
                        .then(CommandManager.literal("chunks")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .executes(context -> {
                                    if (savedChunks.isEmpty()) {
                                        context.getSource().sendMessage(Text.literal("§cNo chunks have been saved."));
                                    } else {
                                        context.getSource().sendMessage(Text.literal("§aSaved chunks:"));
                                        for (Map.Entry<String, ChunkData> entry : savedChunks.entrySet()) {
                                            String blockInfo = entry.getValue().blockType != null ?
                                                    " - Block: " + entry.getValue().blockType : " - No block set";
                                            String message = String.format("§f- %s: Chunk X: %d, Z: %d%s",
                                                    entry.getKey(),
                                                    entry.getValue().x,
                                                    entry.getValue().z,
                                                    blockInfo);
                                            context.getSource().sendMessage(Text.literal(message));
                                        }
                                    }
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("delchunk")
                                .requires(source -> {
                                    ServerPlayerEntity player = source.getPlayer();
                                    return player != null && blockWatcher.hasInspectionPermission(player);
                                })
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            String chunkName = StringArgumentType.getString(context, "name");
                                            if (savedChunks.remove(chunkName) != null) {
                                                saveConfig();
                                                context.getSource().sendMessage(Text.literal("§aChunk '" + chunkName + "' deleted."));
                                            } else {
                                                context.getSource().sendMessage(Text.literal("§cNo chunk found with name '" + chunkName + "'."));
                                            }
                                            return 1;
                                        })
                                )
                        )
        );
    }

    private static void saveConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            configFile.getParentFile().mkdirs();
            String json = GSON.toJson(savedChunks);
            Files.write(Paths.get(CONFIG_FILE), json.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadConfig() {
        try {
            Path configPath = Paths.get(CONFIG_FILE);
            if (Files.exists(configPath)) {
                String json = new String(Files.readAllBytes(configPath));
                TypeToken<HashMap<String, ChunkData>> typeToken = new TypeToken<>() {};
                savedChunks = GSON.fromJson(json, typeToken.getType());

                for (ChunkData chunk : savedChunks.values()) {
                    chunk.initTransientFields();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class ChunkData {
        final int x;
        final int z;
        String blockType;
        private transient Map<UUID, Long> lastTriggerTime;

        public ChunkData(int x, int z) {
            this.x = x;
            this.z = z;
            initTransientFields();
        }

        public void initTransientFields() {
            if (lastTriggerTime == null) {
                lastTriggerTime = new HashMap<>();
            }
        }

        public void setBlockType(String blockType) {
            this.blockType = blockType;
        }

        public boolean canTriggerFor(UUID playerId) {
            if (lastTriggerTime == null) {
                initTransientFields();
            }

            long now = System.currentTimeMillis();
            Long lastTrigger = lastTriggerTime.get(playerId);
            if (lastTrigger == null || now - lastTrigger > 1000) {
                lastTriggerTime.put(playerId, now);
                return true;
            }
            return false;
        }
    }
}

class BlockWatcher {
    private Map<String, ChunkChecker.ChunkData> savedChunks;
    private final Map<UUID, BlockPos> lastPlayerPositions = new HashMap<>();
    private final Map<UUID, Boolean> firstStepMap = new HashMap<>();
    private final Map<UUID, Long> lastCheckTime = new ConcurrentHashMap<>();
    private List<String> watchedItems;
    private boolean isActive = true;

    public void toggleActive() {
        isActive = !isActive;
    }

    public boolean isActive() {
        return isActive;
    }

    public void init(Map<String, ChunkChecker.ChunkData> chunks, List<String> items) {
        this.savedChunks = chunks;
        this.watchedItems = items;
        setupBlockWatcher();
    }

    private void setupBlockWatcher() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            server.getPlayerManager().getPlayerList().forEach(this::checkPlayerBlock);
        });
    }

    private void checkPlayerBlock(ServerPlayerEntity player) {
        if (!isActive) return;
        BlockPos currentPos = new BlockPos(
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY() - 1),
                (int) Math.floor(player.getZ())
        );

        BlockPos lastPos = lastPlayerPositions.get(player.getUuid());
        if (lastPos != null && lastPos.equals(currentPos)) {
            return;
        }
        lastPlayerPositions.put(player.getUuid(), currentPos);

        int chunkX = Math.floorDiv(currentPos.getX(), 16);
        int chunkZ = Math.floorDiv(currentPos.getZ(), 16);

        boolean inWatchedChunk = false;
        String requiredBlockType = null;

        for (Map.Entry<String, ChunkChecker.ChunkData> entry : savedChunks.entrySet()) {
            ChunkChecker.ChunkData chunkData = entry.getValue();

            if (chunkData.x == chunkX && chunkData.z == chunkZ) {
                if (chunkData.blockType != null) {
                    inWatchedChunk = true;
                    requiredBlockType = chunkData.blockType;
                    break;
                }
            }
        }

        if (!inWatchedChunk) {
            // Reset when player leaves watched chunk
            firstStepMap.remove(player.getUuid());
            lastCheckTime.remove(player.getUuid());
            return;
        }

        Block blockUnder = player.getWorld().getBlockState(currentPos).getBlock();
        String blockId = Registries.BLOCK.getId(blockUnder).toString();

        if (blockId.equals(requiredBlockType)) {
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastCheckTime.get(player.getUuid());
            boolean isFirstStep = !firstStepMap.getOrDefault(player.getUuid(), false);

            if (isFirstStep || lastTime == null || (currentTime - lastTime >= 5000)) {
                checkPlayerInventory(player, isFirstStep);
                lastCheckTime.put(player.getUuid(), currentTime);
                firstStepMap.put(player.getUuid(), true);
            }
        }
    }

    private void checkPlayerInventory(ServerPlayerEntity player, boolean isFirstStep) {
        Map<String, Integer> foundItems = new HashMap<>();

        // Check main inventory
        for (ItemStack itemStack : player.getInventory().main) {
            checkItemStack(itemStack, foundItems);
        }

        // Check armor slots
        for (ItemStack itemStack : player.getInventory().armor) {
            checkItemStack(itemStack, foundItems);
        }

        // Check offhand
        checkItemStack(player.getOffHandStack(), foundItems);

        String playerName = player.getName().getString();

        // Get all players with the inspection permission
        List<ServerPlayerEntity> inspectors = player.getServer().getPlayerManager().getPlayerList().stream()
                .filter(this::hasInspectionPermission)
                .collect(Collectors.toList());

        // Send messages immediately
        if (foundItems.isEmpty()) {
            sendToInspectors(inspectors, Text.literal(String.format(
                    "§a%s is clear",
                    playerName
            )));
        } else {
            foundItems.forEach((item, count) -> {
                String itemName = item.replace("minecraft:", "").replace("_", " ");
                sendToInspectors(inspectors, Text.literal(String.format(
                        "§c%s has %d %s",
                        playerName,
                        count,
                        itemName
                )));
            });
        }
    }

    boolean hasInspectionPermission(ServerPlayerEntity player) {
        return player != null && (
                Permissions.check(player, "sackinspector.caninspectsacks", false) ||
                        player.hasPermissionLevel(2)
        );
    }

    private void checkItemStack(ItemStack itemStack, Map<String, Integer> foundItems) {
        if (itemStack != null && !itemStack.isEmpty()) {
            String itemId = Registries.ITEM.getId(itemStack.getItem()).toString();
            if (watchedItems.contains(itemId)) {
                foundItems.merge(itemId, itemStack.getCount(), Integer::sum);
            }
        }
    }

    private void sendToInspectors(List<ServerPlayerEntity> inspectors, Text message) {
        // Only send messages to players with the inspection permission
        inspectors.stream()
                .filter(this::hasInspectionPermission)
                .forEach(inspector -> inspector.sendMessage(message));
    }
}