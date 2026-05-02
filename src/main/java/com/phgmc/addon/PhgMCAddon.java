package com.phgmc.addon;

import com.phgmc.addon.modules.BaseFinder;
import com.phgmc.addon.modules.BiomeBorderMap;
import com.phgmc.addon.modules.HighPing;
import com.phgmc.addon.modules.HitboxPvP;
import com.phgmc.addon.modules.PearlPredict;
import com.phgmc.addon.modules.PlayerCam;
import com.phgmc.addon.modules.PlayerTracker;
import com.phgmc.addon.modules.SlimeChunk;
import com.phgmc.addon.modules.SoundTracker;
import com.phgmc.addon.modules.StashFinder;
import com.phgmc.addon.modules.StructureFinder;
import com.phgmc.addon.modules.StudentAura;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

import java.util.function.Supplier;

public class PhgMCAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category PhgMC         = new Category("PhgMC");
    public static final Category PhgMC_PvP     = new Category("PhgMC PvP");
    public static final Category PhgMC_Support = new Category("phg support");

    @Override
    public void onInitialize() {
        LOG.info("[PhgMC] onInitialize: registering modules");
        safeAdd("HighPing",        HighPing::new);
        safeAdd("StudentAura",     StudentAura::new);
        safeAdd("PearlPredict",    PearlPredict::new);
        safeAdd("StructureFinder", StructureFinder::new);
        safeAdd("SlimeChunk",      SlimeChunk::new);
        safeAdd("PlayerCam",       PlayerCam::new);
        safeAdd("BiomeBorderMap",  BiomeBorderMap::new);
        safeAdd("PlayerTracker",   PlayerTracker::new);
        safeAdd("StashFinder",     StashFinder::new);
        safeAdd("BaseFinder",      BaseFinder::new);
        safeAdd("SoundTracker",    SoundTracker::new);
        safeAdd("HitboxPvP",       HitboxPvP::new);
        LOG.info("[PhgMC] onInitialize: done");
    }

    private static void safeAdd(String label, Supplier<? extends Module> factory) {
        try {
            Module m = factory.get();
            Modules.get().add(m);
            LOG.info("[PhgMC] + {} ({})", label, m.name);
        } catch (Throwable t) {
            LOG.error("[PhgMC] FAILED to register {}: {}", label, t.toString(), t);
        }
    }

    @Override
    public void onRegisterCategories() {
        LOG.info("[PhgMC] onRegisterCategories");
        Modules.registerCategory(PhgMC);
        Modules.registerCategory(PhgMC_PvP);
        Modules.registerCategory(PhgMC_Support);
    }

    @Override
    public String getPackage() {
        return "com.phgmc.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("WusElaina-007", "Src-meomeo");
    }
}
