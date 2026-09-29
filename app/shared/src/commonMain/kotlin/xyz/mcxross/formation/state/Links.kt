package xyz.mcxross.formation.state

import xyz.mcxross.formation.link.HostAddress

data class JoinTarget(val address: HostAddress?, val code: String?)

object Links {
  const val SCHEME = "formation"
  const val WEB_HOST = "formation.mcxross.xyz"

  fun join(address: HostAddress, code: String): String = "$SCHEME://join?h=$address&c=$code"

  fun parse(text: String): JoinTarget? {
    val trimmed = text.trim()
    val query = trimmed.substringAfter('?', "")
    val params =
      query
        .split('&')
        .mapNotNull { part ->
          val eq = part.indexOf('=')
          if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }
        .toMap()
    val host = params["h"]?.let(HostAddress::parse)
    return when {
      trimmed.startsWith("$SCHEME://join") ->
        JoinTarget(host, params["c"]).takeIf { host != null || it.code != null }
      trimmed.startsWith("https://$WEB_HOST/j/") -> {
        val code =
          trimmed.removePrefix("https://$WEB_HOST/j/").substringBefore('?').takeIf {
            it.isNotBlank()
          }
        JoinTarget(host, code)
      }
      else -> null
    }
  }
}
