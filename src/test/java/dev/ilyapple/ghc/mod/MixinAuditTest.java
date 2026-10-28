package dev.ilyapple.ghc.mod;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.MixinEnvironment;

/** Loads every mixin target from the real 26.1.2 classes, so a wrong method name fails the build. */
class MixinAuditTest {
	@Test
	void mixinsApply() {
		MixinEnvironment.getCurrentEnvironment().audit();
	}
}
