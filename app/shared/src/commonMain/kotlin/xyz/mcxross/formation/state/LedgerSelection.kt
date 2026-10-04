package xyz.mcxross.formation.state

internal fun selectLedger(saved: String?, debug: Boolean): LedgerMode =
  if (debug) LedgerMode.entries.firstOrNull { it.name == saved } ?: LedgerMode.SOLANA
  else LedgerMode.SOLANA
