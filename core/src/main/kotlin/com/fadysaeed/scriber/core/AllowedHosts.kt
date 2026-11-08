package com.fadysaeed.scriber.core

/**
 * The only places Scriber may ever contact, and only to download models.
 *
 * This list must match app/src/main/res/xml/network_security_config.xml, which is what Android
 * itself enforces; a unit test fails the build if the two drift apart.
 * Downloads also redirect inside these domains (huggingface.co to *.hf.co, github.com to
 * release-assets.githubusercontent.com).
 */
object AllowedHosts {
    val domains: List<String> = listOf("huggingface.co", "hf.co", "github.com", "githubusercontent.com")

    fun isAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trimEnd('.')
        return domains.any { h == it || h.endsWith(".$it") }
    }
}
