package org.maiminhdung.customenderchest.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Applies the configured command label and aliases to the plugin command.
 */
public final class CommandRegistrationManager {

    private static final String FALLBACK_PREFIX = "customenderchest";
    private static final Pattern VALID_LABEL = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final EnderChest plugin;
    private final PluginCommand command;
    private final CommandMap commandMap;

    private volatile String activeLabel;
    private volatile List<String> activeAliases;

    public CommandRegistrationManager(EnderChest plugin, PluginCommand command) {
        this.plugin = plugin;
        this.command = command;
        this.commandMap = plugin.getServer().getCommandMap();
        this.activeLabel = command.getLabel().toLowerCase(Locale.ROOT);
        this.activeAliases = normalizeAliases(command.getAliases(), activeLabel);
    }

    public boolean applyConfiguredCommands() {
        Object rawMain = plugin.getConfig().get("commands.main", "cec");
        if (!(rawMain instanceof String mainLabel)) {
            plugin.getLogger().warning("commands.main must be a string. Command reload cancelled.");
            return false;
        }
        String configuredLabel = normalizeLabel(mainLabel);
        if (configuredLabel == null) {
            plugin.getLogger().warning("Invalid commands.main value. Use 1-32 lowercase letters, numbers, '_' or '-'.");
            return false;
        }

        boolean aliasesConfigured = plugin.getConfig().getKeys(true).contains("commands.aliases");
        Object aliasValue = plugin.getConfig().get("commands.aliases");
        List<?> rawAliases;
        if (!aliasesConfigured) {
            rawAliases = List.of("ec", "customenderchest", "customec");
        } else if (aliasValue == null) {
            rawAliases = List.of();
        } else if (aliasValue instanceof List<?> aliasList) {
            rawAliases = aliasList;
        } else {
            plugin.getLogger().warning("commands.aliases must be a YAML list. Command reload cancelled.");
            return false;
        }
        List<String> aliases = validateAndConvertAliases(rawAliases);
        if (aliases == null) {
            return false;
        }
        List<String> configuredAliases = normalizeAliases(aliases, configuredLabel);

        if (registrationAlreadyMatches(configuredLabel, configuredAliases)) {
            return true;
        }

        Set<String> requestedLabels = new LinkedHashSet<>();
        requestedLabels.add(configuredLabel);
        requestedLabels.addAll(configuredAliases);
        for (String label : requestedLabels) {
            Command existing = commandMap.getCommand(label);
            if (existing != null && existing != command) {
                plugin.getLogger().warning("Cannot register /" + label
                        + " because it is already owned by another command. Command reload cancelled.");
                return false;
            }
        }

        String previousLabel = activeLabel;
        List<String> previousAliases = List.copyOf(activeAliases);

        try {
            unregisterCurrentMappings(requestedLabels);
            command.setLabel(configuredLabel);
            command.setAliases(configuredAliases);

            boolean registeredDirectly = commandMap.register(configuredLabel, FALLBACK_PREFIX, command);
            if (!registeredDirectly || commandMap.getCommand(configuredLabel) != command) {
                throw new IllegalStateException("The server did not register /" + configuredLabel + " directly");
            }
            for (String alias : configuredAliases) {
                if (commandMap.getCommand(alias) != command) {
                    throw new IllegalStateException("The server did not register alias /" + alias + " directly");
                }
            }

            activeLabel = configuredLabel;
            activeAliases = List.copyOf(configuredAliases);
            updatePlayerCommandTrees();
            plugin.getLogger().info("Registered command /" + activeLabel + formatAliases(activeAliases));
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to apply configured commands; restoring previous command.", error);
            restorePreviousRegistration(previousLabel, previousAliases);
            return false;
        }
    }

    public String getPrimaryCommand() {
        return "/" + activeLabel;
    }

    private boolean registrationAlreadyMatches(String configuredLabel, List<String> configuredAliases) {
        if (!configuredLabel.equals(activeLabel) || !configuredAliases.equals(activeAliases)) {
            return false;
        }
        if (commandMap.getCommand(configuredLabel) != command) {
            return false;
        }
        return configuredAliases.stream().allMatch(alias -> commandMap.getCommand(alias) == command);
    }

    private List<String> validateAndConvertAliases(List<?> aliases) {
        List<String> validated = new ArrayList<>();
        for (Object value : aliases) {
            if (!(value instanceof String alias)) {
                plugin.getLogger().warning("Every commands.aliases entry must be a string. Command reload cancelled.");
                return null;
            }
            String normalized = normalizeLabel(alias);
            if (normalized == null) {
                plugin.getLogger().warning("Invalid command alias '" + alias
                        + "'. Use 1-32 lowercase letters, numbers, '_' or '-'.");
                return null;
            }
            validated.add(normalized);
        }
        return validated;
    }

    private String normalizeLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return VALID_LABEL.matcher(normalized).matches() ? normalized : null;
    }

    private List<String> normalizeAliases(List<String> aliases, String primaryLabel) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String alias : aliases) {
            String label = normalizeLabel(alias);
            if (label != null && !label.equals(primaryLabel)) {
                normalized.add(label);
            }
        }
        return new ArrayList<>(normalized);
    }

    private void unregisterCurrentMappings(Set<String> retainedLabels) {
        command.unregister(commandMap);
        Map<String, Command> knownCommands = getMutableKnownCommands();
        boolean hasObsoleteDirectMappings = knownCommands.entrySet().stream()
                .anyMatch(entry -> entry.getValue() == command
                        && !entry.getKey().contains(":")
                        && !retainedLabels.contains(entry.getKey().toLowerCase(Locale.ROOT)));
        if (!hasObsoleteDirectMappings) {
            return;
        }
        try {
            knownCommands.entrySet().removeIf(entry -> entry.getValue() == command
                    && !entry.getKey().contains(":")
                    && !retainedLabels.contains(entry.getKey().toLowerCase(Locale.ROOT)));
        } catch (UnsupportedOperationException e) {
            plugin.getLogger().warning("Unable to remove old command mappings directly from command map.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getMutableKnownCommands() {
        try {
            Class<?> clazz = commandMap.getClass();
            while (clazz != null && clazz != Object.class) {
                try {
                    Field field = clazz.getDeclaredField("knownCommands");
                    field.setAccessible(true);
                    Object result = field.get(commandMap);
                    if (result instanceof Map<?, ?> map) {
                        return (Map<String, Command>) map;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Try superclass (e.g. SimpleCommandMap)
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to access knownCommands via reflection", error);
        }
        return commandMap.getKnownCommands();
    }

    private void restorePreviousRegistration(String previousLabel, List<String> previousAliases) {
        try {
            Set<String> previousLabels = new LinkedHashSet<>();
            previousLabels.add(previousLabel);
            previousLabels.addAll(previousAliases);
            unregisterCurrentMappings(previousLabels);
            command.setLabel(previousLabel);
            command.setAliases(previousAliases);
            commandMap.register(previousLabel, FALLBACK_PREFIX, command);
            activeLabel = previousLabel;
            activeAliases = List.copyOf(previousAliases);
            updatePlayerCommandTrees();
        } catch (Exception restoreError) {
            plugin.getLogger().log(Level.SEVERE, "Unable to restore the previous command registration.", restoreError);
        }
    }

    private void updatePlayerCommandTrees() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Scheduler.runEntityTask(player, player::updateCommands);
        }
    }

    private String formatAliases(List<String> aliases) {
        return aliases.isEmpty() ? "" : " with aliases " + aliases.stream().map(alias -> "/" + alias).toList();
    }
}
