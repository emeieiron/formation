package xyz.mcxross.formation.session

// Check the persisted commitment and every collected acknowledgement before restoring a referee.
fun validatedCompletion(snapshot: SessionSnapshot): Stage.Won {
  val won = snapshot.stage as? Stage.Won ?: error("Only a completed round can be restored")
  val expected = Sealing.seal(snapshot.formation.opportunity, snapshot.formation.session, snapshot.players, won.result)
  require(expected.message == won.seal.message && expected.root == won.seal.root &&
    expected.roster == won.seal.roster && expected.ownerAmount == won.seal.ownerAmount &&
    expected.required == won.seal.required) { "The saved completion commitment is invalid" }
  require(won.seal.signed.distinct().size == won.seal.signed.size &&
    won.seal.signed.all { id ->
      val player = snapshot.player(id)
      val signature = won.seal.signatures[id]
      player != null && signature != null && Sealing.verify(won.seal, player, signature)
    }) { "The saved seal acknowledgement is invalid" }
  return won
}
