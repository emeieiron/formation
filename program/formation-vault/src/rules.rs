use anchor_lang::prelude::*;

use crate::VaultError;

const LEAF: u8 = 0;
const NODE: u8 = 1;
pub const UNBOUND: [u8; 32] = [0; 32];

/// An owner gets `weight` times what each of `guests` guests gets, and keeps the rounding dust, so the
/// total is exactly the budget however many guests there are.
pub fn split(budget: u64, weight: u16, guests: u8) -> Result<(u64, u64)> {
    require!(guests > 0, VaultError::RosterSize);
    let share = budget / (weight as u64 + guests as u64);
    let owner = budget.checked_sub(share * guests as u64).ok_or(VaultError::Overflow)?;
    Ok((owner, share))
}

/// Leaves and nodes are domain-separated and pairs are hashed in sorted order, so proofs carry no
/// left/right bits.
pub fn roster_root(index: u8, claim_key: &Pubkey, wallet: &[u8; 32], proof: &[[u8; 32]]) -> [u8; 32] {
    let leaf = solana_sha256_hasher::hashv(&[&[LEAF], &[index], claim_key.as_ref(), wallet]).to_bytes();
    proof.iter().fold(leaf, |acc, sibling| {
        let (a, b) = if acc <= *sibling { (acc, *sibling) } else { (*sibling, acc) };
        solana_sha256_hasher::hashv(&[&[NODE], &a, &b]).to_bytes()
    })
}

/// A keyed permutation of `[0, n)`: entry `i` of a draw is selected when `permute(i) < k`. Exactly `k`
/// entries are selected, and each unlock checks its own index without a stored winner list.
pub fn permute(seed: &[u8; 32], n: u32, index: u32) -> u32 {
    if n <= 1 {
        return 0;
    }
    // A balanced Feistel network over the smallest even-bit domain holding n, cycle-walked back into
    // range. The domain is under 4n, so a few walks at most.
    let mut bits = 32 - (n - 1).leading_zeros();
    bits += bits % 2;
    let half = bits / 2;
    let mask = (1u64 << half) - 1;
    let mut x = index as u64;
    loop {
        let (mut left, mut right) = (x >> half, x & mask);
        for round in 0..4u8 {
            let h = solana_sha256_hasher::hashv(&[seed, &[round], &right.to_le_bytes()]).to_bytes();
            let f = u64::from_le_bytes(h[..8].try_into().unwrap()) & mask;
            (left, right) = (right, left ^ f);
        }
        x = (left << half) | right;
        if x < n as u64 {
            return x as u32;
        }
    }
}
