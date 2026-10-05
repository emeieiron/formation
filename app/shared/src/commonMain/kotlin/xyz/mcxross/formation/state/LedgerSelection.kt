package xyz.mcxross.formation.state

internal fun selectLedger(saved: String?, developer: Boolean): LedgerMode =
  if (developer) LedgerMode.entries.firstOrNull { it.name == saved } ?: LedgerMode.SOLANA
  else LedgerMode.SOLANA
