package com.phgmc.addon.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.phgmc.addon.modules.HitboxPvP;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds extra targeting margin to every entity when {@link HitboxPvP} is
 * active, so vanilla raycast picking ({@code ProjectileUtil.raycast})
 * expands the entity bbox by our value before testing intersection.
 *
 * Server-side bbox + reach checks remain vanilla — we cap reach in the
 * module itself to stay within AC tolerance.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @ModifyReturnValue(method = "getTargetingMargin", at = @At("RETURN"))
    private float phgmc$expandTargetingMargin(float original) {
        try {
            HitboxPvP m = Modules.get().get(HitboxPvP.class);
            if (m == null || !m.isActive()) return original;
            Entity self = (Entity) (Object) this;
            float bonus = m.bonusFor(self);
            return original + bonus;
        } catch (Throwable t) {
            return original;
        }
    }
}
