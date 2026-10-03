package me.teamsplus;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class Team {
    public String name;
    public UUID owner;
    public final Set<UUID> members = new LinkedHashSet<>();
    public NamedTextColor color = NamedTextColor.YELLOW;
    public boolean pvp = false;
    public Location home = null;

    public Team(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.members.add(owner);
    }
}
