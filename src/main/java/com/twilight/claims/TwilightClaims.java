package com.twilight.claims;

import com.twilight.claims.command.ClaimCommand;
import com.twilight.claims.command.CommandManager;
import com.twilight.claims.command.TrustCommand;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.placeholder.TwilightClaimsExpansion;
import com.twilight.claims.tool.ShovelToolListener;
import com.twilight.claims.tool.StickToolListener;
import com.twilight.claims.visual.AutoRegionViewListener;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class TwilightClaims extends JavaPlugin {

    private static TwilightClaims instance;

    private ClaimsConfig claimsConfig;
    private Messages messages;
    private WGHook wgHook;
    private ClaimManager claimManager;
    private RegionVisualizer visualizer;

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

        claimsConfig = new ClaimsConfig(this);
        messages = new Messages(this);

        wgHook = new WGHook(this);
        claimManager = new ClaimManager(this, wgHook, claimsConfig);
        visualizer = new RegionVisualizer(this);

        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi != null && papi.isEnabled()) {
            new TwilightClaimsExpansion(this, claimManager, claimsConfig).register();
            getLogger().info("Registered TwilightWGClaims PlaceholderAPI expansion.");
        } else {
            getLogger().warning("PlaceholderAPI not found, %twgc_*% placeholders disabled.");
        }

        CommandManager router = new CommandManager(
                this,
                new ClaimCommand(this, claimsConfig, messages, wgHook, claimManager, visualizer),
                new TrustCommand(messages, wgHook, claimManager)
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

        getLogger().info("TwilightWGClaims enabled.");
    }

    private void register(CommandManager router, String command) {
        Objects.requireNonNull(getCommand(command)).setExecutor(router);
        Objects.requireNonNull(getCommand(command)).setTabCompleter(router);
    }

    @Override
    public void onDisable() {
        getLogger().info("TwilightWGClaims disabled.");
    }
}
