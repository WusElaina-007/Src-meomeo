package com.example.addon;

import com.example.addon.modules.HighPing;
import com.example.addon.modules.PearlPredict;
import com.example.addon.modules.StructureFinder;
import com.example.addon.modules.StudentAura;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category Student = new Category("Student");
    public static final Category Student_pvp = new Category("Student pvp");
    public static final Category Student_esp = new Category("Student esp");

    @Override
    public void onInitialize() {
        LOG.info("Student Addon");
        Modules.get().add(new HighPing());
        Modules.get().add(new StudentAura());
        Modules.get().add(new PearlPredict());
        Modules.get().add(new StructureFinder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(Student);
        Modules.registerCategory(Student_pvp);
        Modules.registerCategory(Student_esp);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("WusElaina-007", "Src-meomeo");
    }
}
