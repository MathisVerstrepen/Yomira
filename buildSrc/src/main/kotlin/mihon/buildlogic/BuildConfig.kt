package mihon.buildlogic

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val enableUpdater: Boolean
    val enableCodeShrink: Boolean
    val includeDependencyInfo: Boolean
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    // KMK -->
    // Yomira has no upstream analytics credentials or update service.
    override val includeTelemetry: Boolean = false
    override val enableUpdater: Boolean = false
    // KMK <--
    override val enableCodeShrink: Boolean = !project.hasProperty("disable-code-shrink")
    override val includeDependencyInfo: Boolean = project.hasProperty("include-dependency-info")
}
