package co.marcin.darkrise.riseresources;

import me.travja.darkrise.core.Debugger;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

public class Data {
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final Long watchdogInterval = Long.valueOf(900L);
    private static final Long watchdogIntervalTicks = Long.valueOf(watchdogInterval.longValue() * 20L);
    public static boolean restrictCropGrowth = true, restrictMelonGrowth = true, restrictTurtleEgg = false, restrictBlockGrowth = true;
    private final Collection<DataEntry> entries = new HashSet<>();
    private final Collection<RegenerationEntry> regenerationEntries = new HashSet<>();
    private final Map<Location, BukkitTask> tasks = new HashMap<>();
    private File storageFile;
    private BukkitTask watchdog = null;

    public static ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public Collection<RegenerationEntry> getRegenerationEntries() {
        return this.regenerationEntries;
    }

    public void setStorageFile(File storageFile) {
        this.storageFile = storageFile;
    }

    public Map<Location, BukkitTask> getTasks() {
        return this.tasks;
    }

    public BukkitTask getWatchdog() {
        return this.watchdog;
    }

    public void load(ConfigurationSection section) {
        this.entries.clear();
        section.getMapList("entries").stream()
                .map(map -> {

                    try {
                        return new DataEntry((Map<String, Object>) map);
                    } catch (Exception e) {
                        RiseResourcesPlugin.getInstance().getLogger().info("Invalid entry: " + e.getMessage());

                        e.printStackTrace();
                        return null;
                    }
                }).filter(Objects::nonNull)
                .forEach(this.entries::add);

        restrictCropGrowth = section.getBoolean("disable_crop_growth");
        restrictMelonGrowth = section.getBoolean("disable_melon_growth");
        restrictTurtleEgg = section.getBoolean("disable_turtle_egg");
        restrictBlockGrowth = section.getBoolean("disable_block_growth");

        RiseResourcesPlugin.getInstance().getLogger().info("Loaded " + this.entries.size() + " entries.");
    }

    public Optional<DataEntry> match(ItemStack material) {
        for (DataEntry entry : this.entries) {
            Debugger.log("Comparing broken " + material.getType() + ":" + material.getDurability() + " with " + entry.getMaterial().getType() + ":" + entry.getMaterial().getDurability());
            if (entry.getMaterial().getType() != material.getType() || entry.getMaterial().getDurability() != material.getDurability()) {
                Debugger.log("Not a match.");
                continue;
            }

            Debugger.log("We have a match.");
            return Optional.of(entry);
        }

        Debugger.log("No matches found.");
        return Optional.empty();
    }

    public Optional<DataEntry> match(BlockBreakEvent event) {
        Debugger.log("Attempting to find a match for " + event.getBlock().getType() + " with data value of " + event.getBlock().getData());
        return match(new ItemStack(event.getBlock().getType(), 1, event.getBlock().getData()));
    }

    public RegenerationEntry addRegenerationEntry(Block block, DataEntry dataEntry, boolean runTask) {
        Validate.notNull(block);
        Validate.notNull(dataEntry);
        RegenerationEntry e = new RegenerationEntry(block.getLocation(), dataEntry);
        this.regenerationEntries.add(e);
        RiseResourcesPlugin.getInstance().getLogger().info("Will be regenerated at " + new Date(e.getRegenTime().longValue()));

        if (runTask) {
            startRegenerationTask(e);
        }

        return e;
    }

    public boolean isRegenerating(Block block) {
        return this.regenerationEntries.stream().anyMatch(e -> e.getLocation().equals(block.getLocation()));
    }


    public void loadRegenerationEntries() throws IOException {
        this.regenerationEntries.clear();

        if (!this.storageFile.exists()) {
            this.storageFile.createNewFile();
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(this.storageFile);
        this.regenerationEntries.addAll((Collection<? extends RegenerationEntry>) configuration.getMapList("data")
                .stream()
                .map(RegenerationEntry::new)
                .collect(Collectors.toList()));
    }

    public void saveRegenerationEntries() throws IOException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(this.storageFile);
        List<Map<String, Object>> list = new ArrayList<>();
        for (RegenerationEntry regenerationEntry : this.regenerationEntries) {
            Map<String, Object> serialize = regenerationEntry.serialize();
            list.add(serialize);
        }
        configuration.set("data", list);
        configuration.save(this.storageFile);
        RiseResourcesPlugin.getInstance().getLogger().info("Saved " + list.size() + " entries.");
    }

    public void startRegenerationTask(RegenerationEntry entry) {
        if (this.tasks.containsKey(entry.getLocation())) {
            return;
        }

        RiseResourcesPlugin.getInstance().getLogger().info(entry.getLocation().toString() + " is gonna be regenerated in " + ((entry.getRegenTime().longValue() - System.currentTimeMillis()) / 1000L) + " seconds");
        this.tasks.put(entry.getLocation(), Bukkit.getScheduler().runTaskLater(RiseResourcesPlugin.getInstance(), entry::regenerate, (entry
                .getRegenTime().longValue() - System.currentTimeMillis()) / 1000L * 20L));
    }

    public void startRegenerationWatchdog() {
        if (this.watchdog != null) {
            throw new IllegalStateException("Watchdog already running");
        }

        this.watchdog = Bukkit.getScheduler().runTaskTimerAsynchronously(RiseResourcesPlugin.getInstance(),
                () -> this.regenerationEntries.stream().filter(RegenerationEntry::isOld)
                        .peek(e -> RiseResourcesPlugin.getInstance().getLogger().info("Watchdog: " + (System.currentTimeMillis() - e.getRegenTime()) / 1000L))
                        .filter(e -> (System.currentTimeMillis() - e.getRegenTime()) / 1000L < watchdogInterval)
                        .peek(e -> RiseResourcesPlugin.getInstance().getLogger().info("Watchdog: " + e.getLocation()))
                        .forEach(this::startRegenerationTask), 0L, watchdogIntervalTicks);
    }

    public void stopRegenerationWatchdog() {
        if (this.watchdog == null/* || this.watchdog.isCancelled()*/) {
            throw new IllegalStateException("Watchdog is not running");
        }

        this.watchdog.cancel();
        this.watchdog = null;
    }

    public void checkChunkRegeneration(Chunk chunk) {
        this.regenerationEntries.stream()
                .filter(e -> e.getLocation().getChunk().equals(chunk))
                .filter(e -> (System.currentTimeMillis() > e.getRegenTime().longValue()))
                .forEach(this::startRegenerationTask);
    }
}