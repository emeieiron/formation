package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekerGenesisTest {
  @Test
  fun aRealSgtBelongsToTheSeekerGroup() {
    assertEquals(SeekerGenesis.MAINNET_GROUP, SeekerGenesis.groupOf(Mainnet.sgt, Mainnet.sgtMint))
    assertTrue(
      SeekerGenesis.isMember(
        Mainnet.sgt,
        SeekerGenesis.TOKEN_2022,
        Mainnet.sgtMint,
        SeekerGenesis.MAINNET_GROUP,
      )
    )
  }

  @Test
  fun membershipCannotBeBorrowedOrForged() {
    val other = SolanaPublicKey(ByteArray(32) { 9 })
    assertNull(SeekerGenesis.groupOf(other, Mainnet.sgtMint))
    assertFalse(
      SeekerGenesis.isMember(
        Mainnet.sgt,
        SolanaPublicKey(ByteArray(32)),
        Mainnet.sgtMint,
        SeekerGenesis.MAINNET_GROUP,
      )
    )
    assertFalse(
      SeekerGenesis.isMember(Mainnet.sgt, SeekerGenesis.TOKEN_2022, Mainnet.sgtMint, other)
    )
    assertNull(SeekerGenesis.groupOf(Mainnet.sgt, Mainnet.sgtMint.copyOf(165)))
    assertNull(SeekerGenesis.groupOf(Mainnet.sgt, Mainnet.sgtMint.copyOf().also { it[165] = 2 }))
    assertNull(
      SeekerGenesis.groupOf(Mainnet.sgt, Mainnet.sgtMint.copyOf(Mainnet.sgtMint.size - 10))
    )
  }

  @Test
  fun readsTheHoldersAccount() {
    assertEquals(
      TokenAccount(Mainnet.sgt, Mainnet.holder, 1uL),
      TokenAccount.decode(Mainnet.sgtAccountData),
    )
    assertNull(TokenAccount.decode(ByteArray(100)))
  }
}
