package br.rustland.killmoney;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * Placeholders do PlaceholderAPI, usados pelo scoreboard do TAB:
 * %rustland_airdrop_active%, _tier, _tier_color, _x, _y, _z, _coords,
 * _time_left, _time_label, _phase, _buyer, _status e _next.
 */
final class AirdropExpansion extends PlaceholderExpansion {

    private final AirdropManager manager;

    AirdropExpansion(AirdropManager manager) {
        this.manager = manager;
    }

    @Override
    public String getIdentifier() {
        return "rustland";
    }

    @Override
    public String getAuthor() {
        return "Rustland";
    }

    @Override
    public String getVersion() {
        return "1.5.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return manager.placeholder(params);
    }
}
