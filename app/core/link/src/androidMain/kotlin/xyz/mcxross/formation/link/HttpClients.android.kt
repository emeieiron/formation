package xyz.mcxross.formation.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClient(): HttpClient =
  HttpClient(OkHttp) {
    engine {
      config {
        // A Seeker is on the same network or nowhere; there is no point waiting long for it.
        connectTimeout(4, TimeUnit.SECONDS)
        readTimeout(0, TimeUnit.SECONDS)
      }
    }
  }
