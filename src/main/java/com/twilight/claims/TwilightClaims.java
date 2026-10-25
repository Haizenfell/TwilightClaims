package com.twilight.claims;

import com.twilight.claims.command.ClaimCommand;
import com.twilight.claims.command.CommandManager;
import com.twilight.claims.command.TrustCommand;
import com.twilight.claims.command.claim.ClaimDeletionService;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.ConfigMigration;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.placeholder.PlaceholderResolver;
import com.twilight.claims.placeholder.TwilightClaimsExpansion;
import com.twilight.claims.tool.ShovelToolListener;
import com.twilight.claims.tool.StickToolListener;
import com.twilight.claims.visual.AutoRegionViewListener;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public class TwilightClaims extends JavaPlugin {

    private static TwilightClaims instance;

    private ClaimsConfig claimsConfig;
    private Messages messages;
    private WGHook wgHook;
    private ClaimManager claimManager;
    private RegionVisualizer visualizer;
    private TwilightClaimsExpansion expansion;
    private ClaimDeletionService deletions;

    public static TwilightClaims getInstance() {
        return instance;
    }

    public RegionVisualizer getVisualizer() {
        return visualizer;
    }

    public Messages getMessages() {
        return messages;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (ConfigMigration.migrate(getConfig())) saveConfig();

        claimsConfig = new ClaimsConfig(this);
        messages = new Messages(this);

        wgHook = new WGHook(this);
        claimManager = new ClaimManager(wgHook, claimsConfig, new PlaceholderResolver(this));
        visualizer = new RegionVisualizer(this);
        deletions = new ClaimDeletionService(wgHook, messages, visualizer);
        Bukkit.getPluginManager().registerEvents(deletions, this);

        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi != null && papi.isEnabled()) {
            expansion = new TwilightClaimsExpansion(this, claimManager);
            if (expansion.register()) {
                getLogger().info("Registered TwilightClaims PlaceholderAPI expansion.");
            } else {
                getLogger().warning("Could not register %tc_*% placeholders: identifier already registered.");
                expansion = null;
            }
        } else {
            getLogger().warning("PlaceholderAPI not found, %tc_*% placeholders disabled.");
        }

        CommandManager router = new CommandManager(
                new ClaimCommand(claimsConfig, messages, wgHook, claimManager, visualizer, deletions),
                new TrustCommand(messages, wgHook), messages
        );

        register(router, "claim");
        register(router, "trust");
        register(router, "untrust");
        register(router, "trustlist");

        ShovelToolListener shovelTool = new ShovelToolListener(
                claimsConfig, messages, wgHook, claimManager, visualizer
        );

        Bukkit.getPluginManager().registerEvents(
                new StickToolListener(claimsConfig, messages, wgHook),
                this
        );

        Bukkit.getPluginManager().registerEvents(shovelTool, this);
        Bukkit.getPluginManager().registerEvents(
                new AutoRegionViewListener(claimsConfig, wgHook, visualizer),
                this
        );

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                claimManager.refresh(event.getPlayer());
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                claimManager.forget(event.getPlayer());
            }
        }, this);
        Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.getOnlinePlayers().forEach(claimManager::refresh), 1L, 20L);

        getLogger().info("TwilightClaims enabled.");
    }

    private void register(CommandManager router, String command) {
        Objects.requireNonNull(getCommand(command)).setExecutor(router);
        Objects.requireNonNull(getCommand(command)).setTabCompleter(router);
    }

    @Override
    public void onDisable() {
        if (deletions != null) deletions.close();
        if (visualizer != null) visualizer.close();
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        Bukkit.getScheduler().cancelTasks(this);
        instance = null;
        getLogger().info("TwilightClaims disabled.");
    }
}
