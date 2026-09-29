package xyz.mcxross.formation.sensors

interface Haptics {
  fun tick()

  fun confirm()

  fun reject()

  fun heavy()

  companion object {
    val None: Haptics =
      object : Haptics {
        override fun tick() {}

        override fun confirm() {}

        override fun reject() {}

        override fun heavy() {}
      }
  }
}
