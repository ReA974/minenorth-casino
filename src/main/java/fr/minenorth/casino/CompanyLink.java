package fr.minenorth.casino;

import com.mojang.logging.LogUtils;
import fr.minenorth.casino.config.CasinoConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Lien avec le mod Entreprises (par réflexion, comme le mod Portes) : retrouve l'entreprise désignée dans la config
 * et son compte bancaire. Seule une entreprise ACTIVE est trouvée.
 */
public final class CompanyLink {
    private CompanyLink() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final String API = "fr.minenorth.entreprises.api.EntrepriseApi";

    public record Casino(int id, String name, UUID account) {}

    private static boolean warned;

    /** L'entreprise du casino, ou null (non configurée, introuvable, inactive, fermée si la config l'exige). */
    @SuppressWarnings("unchecked")
    public static Casino resolve(MinecraftServer s) {
        CasinoConfig cfg = CasinoConfig.get();
        if (cfg.entreprise_id < 0 && cfg.entreprise.isBlank()) return null;
        try {
            Class<?> api = Class.forName(API);
            List<String> rows = (List<String>) api.getMethod("listCompanies", MinecraftServer.class).invoke(null, s);
            for (String row : rows) {
                int bar = row.indexOf('|');
                if (bar < 0) continue;
                int id = Integer.parseInt(row.substring(0, bar));
                String name = row.substring(bar + 1);
                boolean match = cfg.entreprise_id >= 0 ? id == cfg.entreprise_id : name.equalsIgnoreCase(cfg.entreprise.trim());
                if (!match) continue;
                UUID account = (UUID) api.getMethod("accountOf", MinecraftServer.class, int.class).invoke(null, s, id);
                if (account == null) return null;
                if (cfg.exiger_entreprise_ouverte) {
                    boolean open = (Boolean) api.getMethod("isOpen", MinecraftServer.class, int.class).invoke(null, s, id);
                    if (!open) return null;
                }
                return new Casino(id, name, account);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) { warned = true; LOG.error("[Casino] Mod Entreprises inutilisable : {}", e.toString()); }
        }
        return null;
    }
}
