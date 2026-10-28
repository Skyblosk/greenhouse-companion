package dev.ilyapple.ghc.sharedvariables

import org.gradle.api.Plugin

/**
 * A no-op plugin: applying it in settings pulls the other classes of sharedVariables onto the build's classpath
 * (SkyHanni's approach; buildSrc isn't available while settings are configured).
 */
class NoOp : Plugin<Any> {
    override fun apply(target: Any) {}
}
