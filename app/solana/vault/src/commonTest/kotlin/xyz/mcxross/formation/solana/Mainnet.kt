package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import xyz.mcxross.formation.crypto.Base64

// A Seeker Genesis Token and its holder's account, copied from mainnet (also in the program's
// tests).
internal object Mainnet {
  val sgt = SolanaPublicKey.from("JBbD5StDRA4rah3yMYUPEXhs8A9mW7mbnqcvzeMo4dZR")
  val sgtAccount = SolanaPublicKey.from("EduidFzPEXBhMsJQM8GL3kFXRxXurngCRG2VSV1apdvT")
  val holder = SolanaPublicKey.from("634xbmD6cxgWDrniqXTAb2VnziBfhC3Xfii5pMp42GpV")

  const val SGT_MINT_BASE64 =
    "AQAAAOWKqhdAvydh2LouHq0lKwjraNJka0hmiNGggbQhTCm9AQAAAAAAAAAAAQEAAADliqoXQL8nYdi6Lh6tJSsI62jSZGtIZojRoIG0IUwpvQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAARIAQADliqoXQL8nYdi6Lh6tJSsI62jSZGtIZojRoIG0IUwpveWJkm0XmBofiHcH5IkGhI6YGUP+gVGTmnUrqnPiEJ7pDAAgAOWKqhdAvydh2LouHq0lKwjraNJka0hmiNGggbQhTCm9AwAgAOWKqhdAvydh2LouHq0lKwjraNJka0hmiNGggbQhTCm9FgBAAOWKqhdAvydh2LouHq0lKwjraNJka0hmiNGggbQhTCm9/00qSi73/RduZfYs1dbgLjZ060M+1iqbXIwkSJs+AQAXAEgA/00qSi73/RduZfYs1dbgLjZ060M+1iqbXIwkSJs+AQDliZJtF5gaH4h3B+SJBoSOmBlD/oFRk5p1K6pz4hCe6cfYAQAAAAAA"
  const val SGT_ACCOUNT_BASE64 =
    "/00qSi73/RduZfYs1dbgLjZ060M+1iqbXIwkSJs+AQBK0hJ/4v14Iv515q0oxvO9CKveSi3IX9bZ5mfC+UQWFgEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgcAAAA="

  val sgtMint: ByteArray
    get() = Base64.decode(SGT_MINT_BASE64)

  val sgtAccountData: ByteArray
    get() = Base64.decode(SGT_ACCOUNT_BASE64)
}
