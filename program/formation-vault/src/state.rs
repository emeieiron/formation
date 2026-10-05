use anchor_lang::prelude::*;

use crate::{VaultError, BPS, MAX_GUESTS};

pub const MODE_FIRST_COME: u8 = 1;
pub const MODE_DRAW: u8 = 2;

const MINUTE: i64 = 60;
const HOUR: i64 = 60 * MINUTE;
const DAY: i64 = 24 * HOUR;
const YEAR: i64 = 366 * DAY;

/// Every number the rules use. Only the admin changes them, within the bounds `check` enforces, and each
/// contest keeps a copy from when it was created, so a change never touches a funded pool.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub struct Settings {
    /// Stops new contests; funded ones carry on.
    pub paused: bool,
    /// Which modes sponsors may choose: `MODE_FIRST_COME`, `MODE_DRAW` or both.
    pub modes: u8,
    /// An owner gets this many times what each guest gets.
    pub owner_weight: u16,
    /// The smallest share any guest may receive, in the mint's base units.
    pub min_guest_share: u64,
    /// An open contest must fund at least this many budgets.
    pub min_budgets: u16,
    /// How many shares one claim key may take from one contest.
    pub max_per_key: u8,
    /// The most budgets a sponsor may let one SGT take in a first-come contest.
    pub max_wins_per_sgt: u8,
    /// The most guests one budget can pay; at most the 64 claimed bits.
    pub guest_ceiling: u8,
    pub claim_window: i64,
    pub min_play_window: i64,
    pub max_play_window: i64,
    pub min_enter_window: i64,
    pub max_enter_window: i64,
    /// How long a draw request may go unanswered before anyone can request again.
    pub draw_timeout: i64,
}

impl Settings {
    /// Fixed safety bounds: a mistake or a stolen admin key can't break the arithmetic or trap funds.
    pub fn check(&self) -> Result<()> {
        let window = |min: i64, max: i64| 0 < min && min <= max && max <= YEAR;
        require!(self.modes != 0 && self.modes & !(MODE_FIRST_COME | MODE_DRAW) == 0, VaultError::BadSettings);
        require!((1..=100).contains(&self.owner_weight), VaultError::BadSettings);
        require!(self.min_guest_share > 0, VaultError::BadSettings);
        require!(self.min_budgets >= 1, VaultError::BadSettings);
        require!((1..=MAX_GUESTS).contains(&self.max_per_key), VaultError::BadSettings);
        require!(self.max_wins_per_sgt >= 1, VaultError::BadSettings);
        require!((1..=MAX_GUESTS).contains(&self.guest_ceiling), VaultError::BadSettings);
        require!((HOUR..=YEAR).contains(&self.claim_window), VaultError::BadSettings);
        require!(window(self.min_play_window, self.max_play_window), VaultError::BadSettings);
        require!(window(self.min_enter_window, self.max_enter_window), VaultError::BadSettings);
        require!((MINUTE..=7 * DAY).contains(&self.draw_timeout), VaultError::BadSettings);
        require!(self.min_guest_share.checked_mul(self.owner_weight as u64 + 1).is_some(), VaultError::BadSettings);
        Ok(())
    }

    /// Enough to pay an owner and one guest.
    pub fn min_budget(&self) -> Result<u64> {
        self.min_guest_share.checked_mul(self.owner_weight as u64 + 1).ok_or(error!(VaultError::Overflow))
    }

    /// Past this many guests, each guest's share would fall below the minimum.
    pub fn max_guests(&self, budget: u64) -> u8 {
        let fits = (budget / self.min_guest_share).saturating_sub(self.owner_weight as u64);
        fits.min(self.guest_ceiling as u64) as u8
    }
}

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub mint: Pubkey,
    /// The Seeker Genesis Token group this deployment accepts.
    pub sgt_group: Pubkey,
    /// The randomness program draws call.
    pub vrf_program: Pubkey,
    pub settings: Settings,
    pub bump: u8,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum Mode {
    /// No registration or draw: each SGT that unlocks takes one budget until the pool runs out.
    FirstCome,
    /// SGTs register, then a VRF draw selects `bps` of them, each with an equal budget.
    Draw { bps: u16 },
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug, Default)]
pub struct Draw {
    pub seed: [u8; 32],
    pub requested_at: i64,
    pub attempts: u8,
    pub randomness: [u8; 32],
    pub done: bool,
}

#[account]
#[derive(InitSpace)]
pub struct Contest {
    pub sponsor: Pubkey,
    pub nonce: u64,
    pub mint: Pubkey,
    pub vault: Pubkey,
    pub sgt_group: Pubkey,
    pub vrf_program: Pubkey,
    /// `MODE_FIRST_COME` or `MODE_DRAW`, kept fixed-size so every later field has one offset.
    pub mode: u8,
    pub draw_bps: u16,
    /// When set, only this SGT mint may unlock: a reward for one Seeker.
    pub only: Pubkey,
    pub wins_per_sgt: u8,
    /// What arrived when the sponsor funded it.
    pub pool: u64,
    /// The part of the pool no budget has claimed yet.
    pub unallocated: u64,
    pub budget: u64,
    pub max_guests: u8,
    pub settings: Settings,
    pub created_at: i64,
    pub enter_until: i64,
    pub play_until: i64,
    pub entered: u32,
    pub selected: u32,
    pub unlocks: u32,
    pub draw: Draw,
    pub title: [u8; 32],
    /// Hash of the branding the sponsor submits for review; the app shows it only once approved.
    pub branding: [u8; 32],
    pub bump: u8,
}

impl Contest {
    pub fn closes_at(&self) -> Result<i64> {
        self.play_until.checked_add(self.settings.claim_window).ok_or(error!(VaultError::Overflow))
    }

    pub fn draw_bps(&self) -> Option<u16> {
        (self.mode == MODE_DRAW).then_some(self.draw_bps)
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum EntryState {
    Registered,
    Unlocked,
}

/// One budget for one SGT. Keyed by mint, so moving the token to another wallet can't take a second one.
#[account]
#[derive(InitSpace)]
pub struct Entry {
    pub contest: Pubkey,
    pub sgt_mint: Pubkey,
    pub round: u8,
    pub index: u32,
    /// Paid the rent, and gets it back at close.
    pub payer: Pubkey,
    /// The wallet that held the SGT at unlock and received the owner's share.
    pub owner: Pubkey,
    pub state: EntryState,
    pub budget: u64,
    pub roster_root: [u8; 32],
    pub roster_size: u8,
    pub guest_share: u64,
    pub owner_paid: u64,
    /// Bit i is set once guest i has claimed.
    pub claimed: u64,
    pub result: [u8; 32],
    pub unlocked_at: i64,
    pub closes_at: i64,
    pub bump: u8,
}

/// How many shares one claim key has taken from one contest.
#[account]
#[derive(InitSpace)]
pub struct Receipt {
    pub contest: Pubkey,
    pub claim_key: Pubkey,
    pub payer: Pubkey,
    pub count: u8,
    pub closes_at: i64,
    pub bump: u8,
}

pub fn bps_of(n: u64, bps: u16) -> u64 {
    (n * bps as u64).div_ceil(BPS)
}
