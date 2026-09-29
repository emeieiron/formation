package xyz.mcxross.formation.challenge.rush

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

enum class ReactorSystem(val title: String, val verb: String, val hint: String) {
  COOLANT(
    "Coolant",
    "Hold to vent",
    "Heat keeps climbing. Hold to vent it, but don't freeze the core.",
  ),
  CHARGE("Charge", "Tap to pump", "Charge drains away. Tap to keep it in the band."),
  BALANCE("Balance", "Tilt to steady", "The core drifts. Tilt the other way to keep it centred."),
  PRESSURE("Pressure", "Shake to release", "Pressure builds up. Shake to blow it off."),
  SPIN("Spin", "Spin the dial", "The rotor slows down. Spin the dial to keep it turning."),
}

@Serializable
data class Gauge(
  val system: ReactorSystem,
  val value: Float,
  val lo: Float,
  val hi: Float,
  val crew: List<PlayerId>,
) {
  val ok: Boolean
    get() = value in lo..hi

  val strain: Float
    get() = if (value < lo) lo - value else if (value > hi) value - hi else 0f
}

@Serializable
data class RushState(
  val gauges: List<Gauge>,
  val stability: Float,
  val duration: Long,
  val elapsed: Long = 0,
  val level: Int = 1,
)

@Serializable
sealed interface RushInput {
  @Serializable @SerialName("hold") data class Hold(val down: Boolean) : RushInput

  @Serializable @SerialName("tap") data object Tap : RushInput

  @Serializable @SerialName("shake") data object Shake : RushInput

  // -1 to 1; positive pushes the reading up.
  @Serializable @SerialName("tilt") data class Tilt(val lean: Float) : RushInput

  @Serializable @SerialName("spin") data class Spin(val speed: Float) : RushInput
}

internal class RushGame(private val setup: ChallengeSetup) : ChallengeGame<RushState, RushInput> {
  private val random = Random(setup.seed)
  private val duration = duration(setup.difficulty)
  private val holding = mutableSetOf<PlayerId>()
  private val leans = mutableMapOf<PlayerId, Float>()
  private val spins = mutableMapOf<PlayerId, Pair<Float, Long>>()
  private var drift = 0f
  private var last = setup.startAt

  // Kept at full precision here; the state carries rounded copies for the phones.
  private val values: MutableMap<ReactorSystem, Float>
  private var stability = 1f

  override var state: RushState
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    val systems = systems(setup.players.size)
    values = systems.associateWith { START.getValue(it) }.toMutableMap()
    val gauges = systems.mapIndexed { k, s ->
      val (lo, hi) = BANDS.getValue(s)
      Gauge(
        s,
        START.getValue(s),
        lo,
        hi,
        setup.players.filterIndexed { i, _ -> i % systems.size == k },
      )
    }
    state = RushState(gauges, 1f, duration)
  }

  private fun crew(system: ReactorSystem) =
    state.gauges.firstOrNull { it.system == system }?.crew.orEmpty()

  override fun input(from: PlayerId, input: RushInput, now: Long) {
    if (status != GameStatus.Running || now < setup.startAt) return
    when (input) {
      is RushInput.Hold ->
        if (from in crew(ReactorSystem.COOLANT))
          if (input.down) holding += from else holding -= from
      RushInput.Tap -> if (from in crew(ReactorSystem.CHARGE)) bump(ReactorSystem.CHARGE, TAP_BOOST)
      RushInput.Shake ->
        if (from in crew(ReactorSystem.PRESSURE)) bump(ReactorSystem.PRESSURE, -SHAKE_RELEASE)
      is RushInput.Tilt ->
        if (from in crew(ReactorSystem.BALANCE)) leans[from] = input.lean.coerceIn(-1f, 1f)
      is RushInput.Spin ->
        if (from in crew(ReactorSystem.SPIN)) spins[from] = input.speed.coerceIn(0f, 6f) to now
    }
    publish()
  }

  override fun tick(now: Long) {
    if (status != GameStatus.Running || now < setup.startAt) return
    val dt = ((now - last) / 1000f).coerceIn(0f, 0.1f)
    last = now
    val elapsed = now - setup.startAt
    val level = 1 + (elapsed / LEVEL_MS).toInt()
    val f = 1f + 0.2f * (level - 1)

    for (system in values.keys) {
      val v = values.getValue(system)
      values[system] =
        when (system) {
          ReactorSystem.COOLANT -> v + 0.075f * f * dt - if (holding.isNotEmpty()) 0.2f * dt else 0f
          ReactorSystem.CHARGE -> v - 0.07f * f * dt
          ReactorSystem.PRESSURE -> v + 0.06f * f * dt
          ReactorSystem.SPIN -> {
            val speed =
              spins.values.filter { now - it.second < SPIN_STALE_MS }.maxOfOrNull { it.first } ?: 0f
            v - 0.09f * f * dt + speed * 0.1f * dt
          }
          ReactorSystem.BALANCE -> {
            drift += (random.nextFloat() * 2f - 1f) * 1.1f * f * dt
            drift -= drift * 0.6f * dt
            val lean = if (leans.isEmpty()) 0f else leans.values.average().toFloat()
            v + (drift * 0.35f + lean * 0.5f) * dt
          }
        }.coerceIn(0f, 1f)
    }

    publish()
    val strained = state.gauges.count { !it.ok }
    stability =
      (stability - strained * 0.085f * dt + if (strained == 0) 0.05f * dt else 0f).coerceIn(0f, 1f)
    state = state.copy(stability = round(stability), elapsed = elapsed, level = level)

    when {
      stability <= 0f -> {
        val worst = state.gauges.maxBy { it.strain }
        status =
          GameStatus.Lost(
            "let the ${worst.system.title.lowercase()} run away",
            worst.crew.firstOrNull(),
            stats(),
          )
      }
      elapsed >= duration ->
        status = GameStatus.Won("Kept the core alive for ${duration / 1000} s", stats())
    }
  }

  private fun bump(system: ReactorSystem, by: Float) {
    values[system] = (values.getValue(system) + by).coerceIn(0f, 1f)
  }

  private fun publish() {
    state =
      state.copy(gauges = state.gauges.map { it.copy(value = round(values.getValue(it.system))) })
  }

  private fun stats() =
    listOf(
      Stat("Survived", "${state.elapsed / 1000} of ${duration / 1000} s"),
      Stat("Level reached", state.level.toString()),
    )

  companion object {
    const val LEVEL_MS = 12_000L
    const val TAP_BOOST = 0.055f
    const val SHAKE_RELEASE = 0.2f
    const val SPIN_STALE_MS = 450L

    val ORDER =
      listOf(
        ReactorSystem.COOLANT,
        ReactorSystem.CHARGE,
        ReactorSystem.BALANCE,
        ReactorSystem.PRESSURE,
        ReactorSystem.SPIN,
      )

    val START =
      mapOf(
        ReactorSystem.COOLANT to 0.5f,
        ReactorSystem.CHARGE to 0.62f,
        ReactorSystem.BALANCE to 0.5f,
        ReactorSystem.PRESSURE to 0.4f,
        ReactorSystem.SPIN to 0.62f,
      )

    val BANDS =
      mapOf(
        ReactorSystem.COOLANT to (0.3f to 0.7f),
        ReactorSystem.CHARGE to (0.35f to 0.82f),
        ReactorSystem.BALANCE to (0.34f to 0.66f),
        ReactorSystem.PRESSURE to (0.18f to 0.72f),
        ReactorSystem.SPIN to (0.4f to 0.82f),
      )

    fun systems(players: Int) = ORDER.take(players.coerceIn(2, ORDER.size))

    fun duration(difficulty: Difficulty): Long =
      when (difficulty) {
        Difficulty.EASY -> 35_000
        Difficulty.NORMAL -> 45_000
        Difficulty.HARD -> 60_000
        Difficulty.EXTREME -> 75_000
      }

    private fun round(v: Float) = (v * 1000).roundToInt() / 1000f
  }
}
