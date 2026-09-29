package xyz.mcxross.formation.link

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

internal expect fun platformHttpClient(): HttpClient

fun linkHttpClient(): HttpClient =
  platformHttpClient().config { install(WebSockets) { pingIntervalMillis = 5_000 } }
