use anchor_lang::prelude::*;
use anchor_lang::solana_program::instruction::{AccountMeta, Instruction};
use anchor_lang::solana_program::program::invoke;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_2022::Token2022;
use anchor_spl::token_2022_extensions::{token_member_initialize, TokenMemberInitialize};
use anchor_spl::token_interface::{self, CloseAccount, Mint, MintTo, TokenAccount, TokenInterface, TransferChecked};

pub mod rules;
pub mod state;

pub use rules::*;
pub use state::*;

declare_id!("8haw7C2rGLgF4dmn3kciRERtrLmRFQLX6Hg14Hg4Jvg5");

pub const CONFIG_SEED: &[u8] = b"config";
pub const CONTEST_SEED: &[u8] = b"contest";
pub const ENTRY_SEED: &[u8] = b"entry";
pub const RECEIPT_SEED: &[u8] = b"receipt";
/// Signs as the test group's update authority and every test token's mint authority.
pub const TEST_AUTHORITY_SEED: &[u8] = b"test-authority";
pub const TEST_TOKEN_SEED: &[u8] = b"test-token";
/// The group member entry a test token gains on joining the group, with its type and length header.
const GROUP_MEMBER_LEN: usize = 4 + 72;
pub const BPS: u64 = 10_000;
/// Guests fit a u64 bitmap of who has claimed.
pub const MAX_GUESTS: u8 = 64;
pub const MAX_PROOF: usize = 7;

pub const TOKEN_2022: Pubkey = Pubkey::from_str_const("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
const TOKEN_GROUP_MEMBER: u16 = 23;
/// Token-2022 pads extended mints to an account's length and puts the account type after it.
const ACCOUNT_TYPE_OFFSET: usize = 165;
const ACCOUNT_TYPE_MINT: u8 = 1;

pub const VRF_NETWORK_SEED: &[u8] = b"orao-vrf-network-configuration";
pub const VRF_REQUEST_SEED: &[u8] = b"orao-vrf-randomness-request";
/// After the account discriminator, a fulfilled request is: variant 1, client, seed, then 64 bytes of randomness.
const VRF_FULFILLED: u8 = 1;
const VRF_SEED_AT: usize = 8 + 1 + 32;
const VRF_RANDOMNESS_AT: usize = VRF_SEED_AT + 32;

#[program]
pub mod formation_vault {
    use super::*;

    pub fn init_config(ctx: Context<InitConfig>, sgt_group: Pubkey, vrf_program: Pubkey, settings: Settings) -> Result<()> {
        settings.check()?;
        ctx.accounts.config.set_inner(Config {
            admin: ctx.accounts.admin.key(),
            mint: ctx.accounts.mint.key(),
            sgt_group,
            vrf_program,
            settings,
            bump: ctx.bumps.config,
        });
        emit!(SettingsChanged { settings });
        Ok(())
    }

    pub fn set_settings(ctx: Context<AdminOnly>, settings: Settings) -> Result<()> {
        settings.check()?;
        ctx.accounts.config.settings = settings;
        emit!(SettingsChanged { settings });
        Ok(())
    }

    pub fn set_sgt_group(ctx: Context<AdminOnly>, sgt_group: Pubkey) -> Result<()> {
        ctx.accounts.config.sgt_group = sgt_group;
        Ok(())
    }

    pub fn set_vrf_program(ctx: Context<AdminOnly>, vrf_program: Pubkey) -> Result<()> {
        ctx.accounts.config.vrf_program = vrf_program;
        Ok(())
    }

    pub fn set_admin(ctx: Context<AdminOnly>, admin: Pubkey) -> Result<()> {
        ctx.accounts.config.admin = admin;
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub fn create_contest(
        ctx: Context<CreateContest>,
        nonce: u64,
        amount: u64,
        mode: Mode,
        budget: u64,
        only: Pubkey,
        wins_per_sgt: u8,
        enter_until: i64,
        play_until: i64,
        title: [u8; 32],
        branding: [u8; 32],
    ) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let config = &ctx.accounts.config;
        let s = config.settings;
        require!(!s.paused, VaultError::Paused);
        require!(amount > 0, VaultError::ZeroAmount);
        require!((1..=s.max_wins_per_sgt).contains(&wins_per_sgt), VaultError::BadWins);

        let within = |from: i64, until: i64, min: i64, max: i64| until.checked_sub(from).is_some_and(|d| (min..=max).contains(&d));
        let enter_until = match mode {
            Mode::FirstCome => {
                require!(s.modes & MODE_FIRST_COME != 0, VaultError::ModeNotAllowed);
                require!(within(now, play_until, s.min_play_window, s.max_play_window), VaultError::BadWindow);
                now
            }
            Mode::Draw { bps } => {
                require!(s.modes & MODE_DRAW != 0, VaultError::ModeNotAllowed);
                require!((1..=BPS as u16).contains(&bps), VaultError::BadShare);
                require!(only == Pubkey::default(), VaultError::BadMode);
                require!(wins_per_sgt == 1, VaultError::BadWins);
                require!(within(now, enter_until, s.min_enter_window, s.max_enter_window), VaultError::BadWindow);
                require!(within(enter_until, play_until, s.min_play_window, s.max_play_window), VaultError::BadWindow);
                enter_until
            }
        };

        let a = &ctx.accounts;
        let received = deposit(&a.sponsor_token, &a.vault, &a.mint, &a.sponsor, &a.token_program, amount)?;
        let min_budget = s.min_budget()?;
        let (budget, max_guests) = match mode {
            Mode::FirstCome => {
                require!(budget >= min_budget && budget <= received, VaultError::BadBudget);
                // A contest for one Seeker may be any size; an open one must reach enough owners.
                let floor = if only == Pubkey::default() { s.min_budgets as u64 } else { 1 };
                require!(received / budget >= floor, VaultError::PoolTooSmall);
                (budget, s.max_guests(budget))
            }
            Mode::Draw { .. } => {
                require!(received / min_budget >= s.min_budgets as u64, VaultError::PoolTooSmall);
                (0, 0)
            }
        };

        ctx.accounts.contest.set_inner(Contest {
            sponsor: ctx.accounts.sponsor.key(),
            nonce,
            mint: ctx.accounts.mint.key(),
            vault: ctx.accounts.vault.key(),
            sgt_group: config.sgt_group,
            vrf_program: config.vrf_program,
            mode: match mode {
                Mode::FirstCome => MODE_FIRST_COME,
                Mode::Draw { .. } => MODE_DRAW,
            },
            draw_bps: match mode {
                Mode::FirstCome => 0,
                Mode::Draw { bps } => bps,
            },
            only,
            wins_per_sgt,
            pool: received,
            unallocated: received,
            budget,
            max_guests,
            settings: s,
            created_at: now,
            enter_until,
            play_until,
            entered: 0,
            selected: 0,
            unlocks: 0,
            draw: Draw::default(),
            title,
            branding,
            bump: ctx.bumps.contest,
        });
        emit!(ContestCreated { contest: ctx.accounts.contest.key(), sponsor: ctx.accounts.sponsor.key(), mode, pool: received, budget, only, play_until });
        Ok(())
    }

    pub fn register(ctx: Context<Register>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let c = &ctx.accounts.contest;
        require!(c.draw_bps().is_some(), VaultError::BadMode);
        require!(now < c.enter_until, VaultError::EntryClosed);
        verify_sgt(&ctx.accounts.sgt_mint, &c.sgt_group)?;

        let index = c.entered;
        let closes_at = c.closes_at()?;
        ctx.accounts.contest.entered = index.checked_add(1).ok_or(VaultError::Overflow)?;
        let owner = ctx.accounts.owner.key();
        ctx.accounts.entry.set_inner(Entry {
            contest: ctx.accounts.contest.key(),
            sgt_mint: ctx.accounts.sgt_mint.key(),
            round: 0,
            index,
            payer: owner,
            owner,
            state: EntryState::Registered,
            budget: 0,
            roster_root: [0; 32],
            roster_size: 0,
            guest_share: 0,
            owner_paid: 0,
            claimed: 0,
            result: [0; 32],
            unlocked_at: 0,
            closes_at,
            bump: ctx.bumps.entry,
        });
        emit!(Registered { contest: ctx.accounts.contest.key(), sgt_mint: ctx.accounts.sgt_mint.key(), index });
        Ok(())
    }

    pub fn request_draw(ctx: Context<RequestDraw>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let c = &ctx.accounts.contest;
        require!(c.draw_bps().is_some(), VaultError::BadMode);
        require!(now >= c.enter_until && now < c.play_until, VaultError::NotDrawTime);
        require!(c.entered > 0, VaultError::NoEntries);
        require!(!c.draw.done, VaultError::AlreadyDrawn);
        let vrf = c.vrf_program;
        if c.draw.attempts > 0 {
            // A retry must not let anyone discard an answer they've already seen.
            let due = c.draw.requested_at.checked_add(c.settings.draw_timeout).ok_or(VaultError::Overflow)?;
            require!(now >= due, VaultError::DrawPending);
            require_keys_eq!(ctx.accounts.previous.key(), request_address(&c.draw.seed, &vrf), VaultError::BadRandomness);
            require!(fulfilled(&ctx.accounts.previous, &c.draw.seed, &vrf).is_none(), VaultError::DrawPending);
        }

        let seed = solana_sha256_hasher::hashv(&[b"formation.draw", c.key().as_ref(), &[c.draw.attempts]]).to_bytes();
        require_keys_eq!(ctx.accounts.vrf_program.key(), vrf, VaultError::BadRandomness);
        require_keys_eq!(ctx.accounts.network_state.key(), Pubkey::find_program_address(&[VRF_NETWORK_SEED], &vrf).0, VaultError::BadRandomness);
        require_keys_eq!(ctx.accounts.request.key(), request_address(&seed, &vrf), VaultError::BadRandomness);

        let mut data = solana_sha256_hasher::hash(b"global:request_v2").to_bytes()[..8].to_vec();
        data.extend_from_slice(&seed);
        let a = &ctx.accounts;
        invoke(
            &Instruction {
                program_id: vrf,
                accounts: vec![
                    AccountMeta::new(a.payer.key(), true),
                    AccountMeta::new(a.network_state.key(), false),
                    AccountMeta::new(a.treasury.key(), false),
                    AccountMeta::new(a.request.key(), false),
                    AccountMeta::new_readonly(a.system_program.key(), false),
                ],
                data,
            },
            &[
                a.payer.to_account_info(),
                a.network_state.to_account_info(),
                a.treasury.to_account_info(),
                a.request.to_account_info(),
                a.system_program.to_account_info(),
                a.vrf_program.to_account_info(),
            ],
        )?;

        let draw = &mut ctx.accounts.contest.draw;
        draw.seed = seed;
        draw.requested_at = now;
        draw.attempts = draw.attempts.checked_add(1).ok_or(VaultError::Overflow)?;
        emit!(DrawRequested { contest: ctx.accounts.contest.key(), seed });
        Ok(())
    }

    pub fn finalize_draw(ctx: Context<FinalizeDraw>) -> Result<()> {
        let c = &ctx.accounts.contest;
        let bps = c.draw_bps().ok_or(VaultError::BadMode)?;
        require!(c.draw.attempts > 0 && !c.draw.done, VaultError::NotDrawTime);
        require_keys_eq!(ctx.accounts.request.key(), request_address(&c.draw.seed, &c.vrf_program), VaultError::BadRandomness);
        let answer = fulfilled(&ctx.accounts.request, &c.draw.seed, &c.vrf_program).ok_or(VaultError::DrawPending)?;

        // Each budget must pay an owner and a guest, so a small pool selects fewer than `bps` asks for.
        let affordable = c.pool / c.settings.min_budget()?;
        let selected = bps_of(c.entered as u64, bps).min(affordable) as u32;
        let budget = c.pool / selected as u64;
        let max_guests = c.settings.max_guests(budget);

        let c = &mut ctx.accounts.contest;
        c.draw.randomness = solana_sha256_hasher::hashv(&[b"formation.selection", &answer]).to_bytes();
        c.draw.done = true;
        c.selected = selected;
        c.budget = budget;
        c.max_guests = max_guests;
        emit!(Drawn { contest: c.key(), entered: c.entered, selected, budget, randomness: c.draw.randomness });
        Ok(())
    }

    /// First come: the SGT's holder takes the next budget while the pool lasts.
    pub fn unlock(ctx: Context<Unlock>, round: u8, roster_root: [u8; 32], roster_size: u8, result: [u8; 32]) -> Result<()> {
        let c = &ctx.accounts.contest;
        require!(c.mode == MODE_FIRST_COME, VaultError::BadMode);
        require!(round < c.wins_per_sgt, VaultError::BadWins);
        let a = &mut *ctx.accounts;
        a.entry.set_inner(Entry {
            contest: a.contest.key(),
            sgt_mint: a.sgt_mint.key(),
            round,
            index: a.contest.unlocks,
            payer: a.owner.key(),
            owner: a.owner.key(),
            state: EntryState::Registered,
            budget: 0,
            roster_root: [0; 32],
            roster_size: 0,
            guest_share: 0,
            owner_paid: 0,
            claimed: 0,
            result: [0; 32],
            unlocked_at: 0,
            closes_at: a.contest.closes_at()?,
            bump: ctx.bumps.entry,
        });
        settle(&mut ctx.accounts.contest, &mut ctx.accounts.entry, &ctx.accounts.owner, &ctx.accounts.sgt_mint, &ctx.accounts.vault, &ctx.accounts.owner_token, &ctx.accounts.mint, &ctx.accounts.token_program, roster_root, roster_size, result)
    }

    /// Draw: a selected entry's current holder plays its budget.
    pub fn unlock_drawn(ctx: Context<UnlockDrawn>, roster_root: [u8; 32], roster_size: u8, result: [u8; 32]) -> Result<()> {
        let c = &ctx.accounts.contest;
        require!(c.draw.done, VaultError::NotDrawTime);
        require!(ctx.accounts.entry.state == EntryState::Registered, VaultError::AlreadyUnlocked);
        require!(permute(&c.draw.randomness, c.entered, ctx.accounts.entry.index) < c.selected, VaultError::NotSelected);
        settle(&mut ctx.accounts.contest, &mut ctx.accounts.entry, &ctx.accounts.owner, &ctx.accounts.sgt_mint, &ctx.accounts.vault, &ctx.accounts.owner_token, &ctx.accounts.mint, &ctx.accounts.token_program, roster_root, roster_size, result)
    }

    pub fn claim(ctx: Context<Claim>, index: u8, proof: Vec<[u8; 32]>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let e = &ctx.accounts.entry;
        require!(e.state == EntryState::Unlocked, VaultError::NotUnlocked);
        require!(now < e.closes_at, VaultError::ClaimWindowClosed);
        require!(index < e.roster_size, VaultError::NotInRoster);
        let bit = 1u64 << index;
        require!(e.claimed & bit == 0, VaultError::AlreadyClaimed);
        require!(proof.len() <= MAX_PROOF, VaultError::ProofTooLong);
        // A guest who named a wallet at the seal is paid there by whoever cranks the claim; one who
        // didn't must sign with their claim key and may send the share anywhere.
        let claimer = ctx.accounts.claimer.key();
        let recipient = ctx.accounts.recipient.key();
        let bound = roster_root(index, &claimer, &recipient.to_bytes(), &proof) == e.roster_root;
        let open = ctx.accounts.claimer.is_signer && roster_root(index, &claimer, &UNBOUND, &proof) == e.roster_root;
        require!(bound || open, VaultError::NotInRoster);

        let c = &ctx.accounts.contest;
        let receipt = &mut ctx.accounts.receipt;
        require!(receipt.count < c.settings.max_per_key, VaultError::ClaimLimit);
        if receipt.count == 0 {
            receipt.contest = c.key();
            receipt.claim_key = claimer;
            receipt.payer = ctx.accounts.payer.key();
            receipt.closes_at = e.closes_at;
            receipt.bump = ctx.bumps.receipt;
        }
        receipt.count += 1;

        let amount = e.guest_share;
        pay_out(&ctx.accounts.vault, &ctx.accounts.recipient_token, &ctx.accounts.mint, c, &ctx.accounts.token_program, amount)?;
        ctx.accounts.entry.claimed |= bit;
        emit!(Claimed { contest: c.key(), entry: ctx.accounts.entry.key(), index, claimer, recipient, amount });
        Ok(())
    }

    /// Gives `wallet` one test Genesis Token in the config's group, so it can host where real tokens don't
    /// exist. The token's address comes from the wallet, so a second one can't be minted.
    pub fn mint_test_token(ctx: Context<MintTestToken>) -> Result<()> {
        require!(ctx.accounts.config.settings.test_tokens, VaultError::TestTokensOff);
        let a = &ctx.accounts;
        let seeds: &[&[u8]] = &[TEST_AUTHORITY_SEED, &[ctx.bumps.authority]];

        // Joining the group appends a member entry to the mint, which Token-2022 grows in place.
        let mint_info = a.mint.to_account_info();
        let needed = Rent::get()?.minimum_balance(mint_info.data_len() + GROUP_MEMBER_LEN);
        let top_up = needed.saturating_sub(mint_info.lamports());
        if top_up > 0 {
            anchor_lang::system_program::transfer(
                CpiContext::new(
                    a.system_program.key(),
                    anchor_lang::system_program::Transfer { from: a.payer.to_account_info(), to: mint_info.clone() },
                ),
                top_up,
            )?;
        }
        token_member_initialize(CpiContext::new_with_signer(
            a.token_program.key(),
            TokenMemberInitialize {
                program_id: a.token_program.to_account_info(),
                member: mint_info.clone(),
                member_mint: mint_info.clone(),
                member_mint_authority: a.authority.to_account_info(),
                group: a.group.to_account_info(),
                group_update_authority: a.authority.to_account_info(),
            },
            &[seeds],
        ))?;
        token_interface::mint_to(
            CpiContext::new_with_signer(
                a.token_program.key(),
                MintTo { mint: mint_info, to: a.token_account.to_account_info(), authority: a.authority.to_account_info() },
                &[seeds],
            ),
            1,
        )?;
        emit!(TestTokenMinted { wallet: a.wallet.key(), mint: a.mint.key() });
        Ok(())
    }

    pub fn close_entry(ctx: Context<CloseEntry>) -> Result<()> {
        require!(Clock::get()?.unix_timestamp >= ctx.accounts.entry.closes_at, VaultError::NotClosable);
        Ok(())
    }

    pub fn close_receipt(ctx: Context<CloseReceipt>) -> Result<()> {
        require!(Clock::get()?.unix_timestamp >= ctx.accounts.receipt.closes_at, VaultError::NotClosable);
        Ok(())
    }

    /// Once every claim window has ended, whatever is left goes back to the sponsor.
    pub fn close_contest(ctx: Context<CloseContest>) -> Result<()> {
        let c = &ctx.accounts.contest;
        require!(Clock::get()?.unix_timestamp >= c.closes_at()?, VaultError::NotClosable);
        let rest = ctx.accounts.vault.amount;
        pay_out(&ctx.accounts.vault, &ctx.accounts.sponsor_token, &ctx.accounts.mint, c, &ctx.accounts.token_program, rest)?;
        let nonce = c.nonce.to_le_bytes();
        let seeds: &[&[u8]] = &[CONTEST_SEED, c.sponsor.as_ref(), &nonce, &[c.bump]];
        token_interface::close_account(CpiContext::new_with_signer(
            ctx.accounts.token_program.key(),
            CloseAccount {
                account: ctx.accounts.vault.to_account_info(),
                destination: ctx.accounts.sponsor.to_account_info(),
                authority: c.to_account_info(),
            },
            &[seeds],
        ))?;
        emit!(ContestClosed { contest: c.key(), returned: rest });
        Ok(())
    }
}

/// Pays the owner's share to the SGT's current holder and records what each guest may claim.
#[allow(clippy::too_many_arguments)]
fn settle<'info>(
    contest: &mut Account<'info, Contest>,
    entry: &mut Account<'info, Entry>,
    owner: &Signer<'info>,
    sgt_mint: &UncheckedAccount<'info>,
    vault: &InterfaceAccount<'info, TokenAccount>,
    owner_token: &InterfaceAccount<'info, TokenAccount>,
    mint: &InterfaceAccount<'info, Mint>,
    token_program: &Interface<'info, TokenInterface>,
    roster_root: [u8; 32],
    roster_size: u8,
    result: [u8; 32],
) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    require!(now < contest.play_until, VaultError::Expired);
    require!(contest.only == Pubkey::default() || contest.only == sgt_mint.key(), VaultError::NotThisSeeker);
    verify_sgt(sgt_mint, &contest.sgt_group)?;
    require!((1..=contest.max_guests).contains(&roster_size), VaultError::RosterSize);
    let budget = contest.budget;
    contest.unallocated = contest.unallocated.checked_sub(budget).ok_or(VaultError::PoolSpent)?;
    contest.unlocks = contest.unlocks.checked_add(1).ok_or(VaultError::Overflow)?;

    let (paid, share) = split(budget, contest.settings.owner_weight, roster_size)?;
    pay_out(vault, owner_token, mint, contest, token_program, paid)?;

    entry.owner = owner.key();
    entry.state = EntryState::Unlocked;
    entry.budget = budget;
    entry.roster_root = roster_root;
    entry.roster_size = roster_size;
    entry.guest_share = share;
    entry.owner_paid = paid;
    entry.result = result;
    entry.unlocked_at = now;
    emit!(Unlocked {
        contest: contest.key(),
        entry: entry.key(),
        sgt_mint: entry.sgt_mint,
        owner: owner.key(),
        owner_paid: paid,
        guest_share: share,
        roster_size,
        roster_root,
        result,
    });
    Ok(())
}

fn request_address(seed: &[u8; 32], vrf: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[VRF_REQUEST_SEED, seed], vrf).0
}

/// The randomness if `request` is a fulfilled answer to `seed`.
fn fulfilled(request: &AccountInfo, seed: &[u8; 32], vrf: &Pubkey) -> Option<[u8; 64]> {
    if request.owner != vrf {
        return None;
    }
    let data = request.try_borrow_data().ok()?;
    let discriminator = &solana_sha256_hasher::hash(b"account:RandomnessV2").to_bytes()[..8];
    if data.len() < VRF_RANDOMNESS_AT + 64 || &data[..8] != discriminator || data[8] != VRF_FULFILLED {
        return None;
    }
    if data[VRF_SEED_AT..VRF_SEED_AT + 32] != seed[..] {
        return None;
    }
    data[VRF_RANDOMNESS_AT..VRF_RANDOMNESS_AT + 64].try_into().ok()
}

/// The member entry must also name this mint, or membership could be borrowed from another mint.
fn verify_sgt(mint: &AccountInfo, group: &Pubkey) -> Result<()> {
    require_keys_eq!(*mint.owner, TOKEN_2022, VaultError::NotASeeker);
    let data = mint.try_borrow_data()?;
    require!(data.len() > ACCOUNT_TYPE_OFFSET && data[ACCOUNT_TYPE_OFFSET] == ACCOUNT_TYPE_MINT, VaultError::NotASeeker);
    let mut at = ACCOUNT_TYPE_OFFSET + 1;
    while at + 4 <= data.len() {
        let kind = u16::from_le_bytes([data[at], data[at + 1]]);
        let len = u16::from_le_bytes([data[at + 2], data[at + 3]]) as usize;
        let start = at + 4;
        require!(start + len <= data.len(), VaultError::NotASeeker);
        if kind == TOKEN_GROUP_MEMBER && len >= 64 {
            let member_mint = Pubkey::new_from_array(data[start..start + 32].try_into().unwrap());
            let member_group = Pubkey::new_from_array(data[start + 32..start + 64].try_into().unwrap());
            require_keys_eq!(member_mint, mint.key(), VaultError::NotASeeker);
            require_keys_eq!(member_group, *group, VaultError::NotASeeker);
            return Ok(());
        }
        if kind == 0 {
            break;
        }
        at = start + len;
    }
    err!(VaultError::NotASeeker)
}

fn deposit<'info>(
    from: &InterfaceAccount<'info, TokenAccount>,
    vault: &InterfaceAccount<'info, TokenAccount>,
    mint: &InterfaceAccount<'info, Mint>,
    authority: &Signer<'info>,
    token_program: &Interface<'info, TokenInterface>,
    amount: u64,
) -> Result<u64> {
    let before = vault.amount;
    token_interface::transfer_checked(
        CpiContext::new(
            token_program.key(),
            TransferChecked {
                from: from.to_account_info(),
                mint: mint.to_account_info(),
                to: vault.to_account_info(),
                authority: authority.to_account_info(),
            },
        ),
        amount,
        mint.decimals,
    )?;
    // Measure what arrived, so a mint with transfer fees cannot overstate the pool.
    let after = TokenAccount::try_deserialize(&mut &vault.to_account_info().data.borrow()[..])?.amount;
    let received = after.checked_sub(before).ok_or(VaultError::Overflow)?;
    require!(received > 0, VaultError::ZeroAmount);
    Ok(received)
}

fn pay_out<'info>(
    vault: &InterfaceAccount<'info, TokenAccount>,
    to: &InterfaceAccount<'info, TokenAccount>,
    mint: &InterfaceAccount<'info, Mint>,
    contest: &Account<'info, Contest>,
    token_program: &Interface<'info, TokenInterface>,
    amount: u64,
) -> Result<()> {
    if amount == 0 {
        return Ok(());
    }
    let nonce = contest.nonce.to_le_bytes();
    let seeds: &[&[u8]] = &[CONTEST_SEED, contest.sponsor.as_ref(), &nonce, &[contest.bump]];
    token_interface::transfer_checked(
        CpiContext::new_with_signer(
            token_program.key(),
            TransferChecked {
                from: vault.to_account_info(),
                mint: mint.to_account_info(),
                to: to.to_account_info(),
                authority: contest.to_account_info(),
            },
            &[seeds],
        ),
        amount,
        mint.decimals,
    )
}

#[derive(Accounts)]
pub struct InitConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(init, payer = admin, space = 8 + Config::INIT_SPACE, seeds = [CONFIG_SEED], bump)]
    pub config: Account<'info, Config>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(constraint = program.programdata_address()? == Some(program_data.key()) @ VaultError::Unauthorized)]
    pub program: Program<'info, crate::program::FormationVault>,
    #[account(constraint = program_data.upgrade_authority_address == Some(admin.key()) @ VaultError::Unauthorized)]
    pub program_data: Account<'info, ProgramData>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct AdminOnly<'info> {
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump, has_one = admin @ VaultError::Unauthorized)]
    pub config: Account<'info, Config>,
}

#[derive(Accounts)]
#[instruction(nonce: u64)]
pub struct CreateContest<'info> {
    #[account(mut)]
    pub sponsor: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump, has_one = mint @ VaultError::WrongMint)]
    pub config: Box<Account<'info, Config>>,
    #[account(
        init,
        payer = sponsor,
        space = 8 + Contest::INIT_SPACE,
        seeds = [CONTEST_SEED, sponsor.key().as_ref(), &nonce.to_le_bytes()],
        bump,
    )]
    pub contest: Box<Account<'info, Contest>>,
    #[account(
        init,
        payer = sponsor,
        associated_token::mint = mint,
        associated_token::authority = contest,
        associated_token::token_program = token_program,
    )]
    pub vault: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(mut, token::mint = mint, token::authority = sponsor, token::token_program = token_program)]
    pub sponsor_token: Box<InterfaceAccount<'info, TokenAccount>>,
    pub mint: Box<InterfaceAccount<'info, Mint>>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Register<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(mut, seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()], bump = contest.bump)]
    pub contest: Box<Account<'info, Contest>>,
    /// CHECK: checked in the handler to be a Seeker Genesis Token.
    pub sgt_mint: UncheckedAccount<'info>,
    #[account(
        constraint = *sgt_account.to_account_info().owner == TOKEN_2022 @ VaultError::NotASeeker,
        constraint = sgt_account.mint == sgt_mint.key() @ VaultError::NotASeeker,
        constraint = sgt_account.owner == owner.key() @ VaultError::NotASeeker,
        constraint = sgt_account.amount == 1 @ VaultError::NotASeeker,
    )]
    pub sgt_account: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init,
        payer = owner,
        space = 8 + Entry::INIT_SPACE,
        seeds = [ENTRY_SEED, contest.key().as_ref(), sgt_mint.key().as_ref(), &[0]],
        bump,
    )]
    pub entry: Box<Account<'info, Entry>>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct RequestDraw<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(mut, seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()], bump = contest.bump)]
    pub contest: Box<Account<'info, Contest>>,
    /// CHECK: the randomness program the contest named at creation.
    pub vrf_program: UncheckedAccount<'info>,
    /// CHECK: the randomness program's configuration PDA; that program checks it.
    #[account(mut)]
    pub network_state: UncheckedAccount<'info>,
    /// CHECK: the randomness program checks its treasury.
    #[account(mut)]
    pub treasury: UncheckedAccount<'info>,
    /// CHECK: the new request PDA, derived from the contest's next seed.
    #[account(mut)]
    pub request: UncheckedAccount<'info>,
    /// CHECK: the last request, which a retry must show is still unanswered; ignored on the first request.
    pub previous: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct FinalizeDraw<'info> {
    #[account(mut, seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()], bump = contest.bump)]
    pub contest: Box<Account<'info, Contest>>,
    /// CHECK: checked in the handler to be the fulfilled answer to the contest's request.
    pub request: UncheckedAccount<'info>,
}

#[derive(Accounts)]
#[instruction(round: u8)]
pub struct Unlock<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(
        mut,
        seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()],
        bump = contest.bump,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub contest: Box<Account<'info, Contest>>,
    /// CHECK: checked in the handler to be a Seeker Genesis Token.
    pub sgt_mint: UncheckedAccount<'info>,
    #[account(
        constraint = *sgt_account.to_account_info().owner == TOKEN_2022 @ VaultError::NotASeeker,
        constraint = sgt_account.mint == sgt_mint.key() @ VaultError::NotASeeker,
        constraint = sgt_account.owner == owner.key() @ VaultError::NotASeeker,
        constraint = sgt_account.amount == 1 @ VaultError::NotASeeker,
    )]
    pub sgt_account: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init,
        payer = owner,
        space = 8 + Entry::INIT_SPACE,
        seeds = [ENTRY_SEED, contest.key().as_ref(), sgt_mint.key().as_ref(), &[round]],
        bump,
    )]
    pub entry: Box<Account<'info, Entry>>,
    #[account(mut)]
    pub vault: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init_if_needed,
        payer = owner,
        associated_token::mint = mint,
        associated_token::authority = owner,
        associated_token::token_program = token_program,
    )]
    pub owner_token: Box<InterfaceAccount<'info, TokenAccount>>,
    pub mint: Box<InterfaceAccount<'info, Mint>>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct UnlockDrawn<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(
        mut,
        seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()],
        bump = contest.bump,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub contest: Box<Account<'info, Contest>>,
    /// CHECK: checked in the handler to be a Seeker Genesis Token.
    pub sgt_mint: UncheckedAccount<'info>,
    #[account(
        constraint = *sgt_account.to_account_info().owner == TOKEN_2022 @ VaultError::NotASeeker,
        constraint = sgt_account.mint == sgt_mint.key() @ VaultError::NotASeeker,
        constraint = sgt_account.owner == owner.key() @ VaultError::NotASeeker,
        constraint = sgt_account.amount == 1 @ VaultError::NotASeeker,
    )]
    pub sgt_account: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        mut,
        seeds = [ENTRY_SEED, contest.key().as_ref(), sgt_mint.key().as_ref(), &[0]],
        bump = entry.bump,
    )]
    pub entry: Box<Account<'info, Entry>>,
    #[account(mut)]
    pub vault: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init_if_needed,
        payer = owner,
        associated_token::mint = mint,
        associated_token::authority = owner,
        associated_token::token_program = token_program,
    )]
    pub owner_token: Box<InterfaceAccount<'info, TokenAccount>>,
    pub mint: Box<InterfaceAccount<'info, Mint>>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Claim<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: proven against the roster in the handler; must sign when no wallet is bound.
    pub claimer: UncheckedAccount<'info>,
    /// CHECK: the wallet bound in the roster, or any wallet a signing claimer chooses.
    pub recipient: UncheckedAccount<'info>,
    #[account(
        seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()],
        bump = contest.bump,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub contest: Box<Account<'info, Contest>>,
    #[account(
        mut,
        seeds = [ENTRY_SEED, contest.key().as_ref(), entry.sgt_mint.as_ref(), &[entry.round]],
        bump = entry.bump,
    )]
    pub entry: Box<Account<'info, Entry>>,
    #[account(
        init_if_needed,
        payer = payer,
        space = 8 + Receipt::INIT_SPACE,
        seeds = [RECEIPT_SEED, contest.key().as_ref(), claimer.key().as_ref()],
        bump,
    )]
    pub receipt: Box<Account<'info, Receipt>>,
    #[account(mut)]
    pub vault: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init_if_needed,
        payer = payer,
        associated_token::mint = mint,
        associated_token::authority = recipient,
        associated_token::token_program = token_program,
    )]
    pub recipient_token: Box<InterfaceAccount<'info, TokenAccount>>,
    pub mint: Box<InterfaceAccount<'info, Mint>>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct MintTestToken<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: any wallet; it receives the token.
    pub wallet: UncheckedAccount<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Box<Account<'info, Config>>,
    /// CHECK: a PDA that only signs; the group names it as update authority.
    #[account(seeds = [TEST_AUTHORITY_SEED], bump)]
    pub authority: UncheckedAccount<'info>,
    /// CHECK: the config's group; Token-2022 checks its update authority when the token joins.
    #[account(mut, address = config.sgt_group @ VaultError::NotASeeker)]
    pub group: UncheckedAccount<'info>,
    #[account(
        init,
        payer = payer,
        seeds = [TEST_TOKEN_SEED, wallet.key().as_ref()],
        bump,
        mint::decimals = 0,
        mint::authority = authority,
        mint::token_program = token_program,
        extensions::group_member_pointer::authority = authority,
        extensions::group_member_pointer::member_address = mint,
    )]
    pub mint: Box<InterfaceAccount<'info, Mint>>,
    #[account(
        init,
        payer = payer,
        associated_token::mint = mint,
        associated_token::authority = wallet,
        associated_token::token_program = token_program,
    )]
    pub token_account: Box<InterfaceAccount<'info, TokenAccount>>,
    pub token_program: Program<'info, Token2022>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct CloseEntry<'info> {
    /// CHECK: receives the rent it paid.
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
    #[account(mut, close = payer, has_one = payer)]
    pub entry: Account<'info, Entry>,
}

#[derive(Accounts)]
pub struct CloseReceipt<'info> {
    /// CHECK: receives the rent it paid.
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
    #[account(mut, close = payer, has_one = payer)]
    pub receipt: Account<'info, Receipt>,
}

#[derive(Accounts)]
pub struct CloseContest<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: bound to the contest by `has_one`; receives the rent and what is left.
    #[account(mut)]
    pub sponsor: UncheckedAccount<'info>,
    #[account(
        mut,
        close = sponsor,
        seeds = [CONTEST_SEED, contest.sponsor.as_ref(), &contest.nonce.to_le_bytes()],
        bump = contest.bump,
        has_one = sponsor,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub contest: Box<Account<'info, Contest>>,
    #[account(mut)]
    pub vault: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init_if_needed,
        payer = payer,
        associated_token::mint = mint,
        associated_token::authority = sponsor,
        associated_token::token_program = token_program,
    )]
    pub sponsor_token: Box<InterfaceAccount<'info, TokenAccount>>,
    pub mint: Box<InterfaceAccount<'info, Mint>>,
    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[event]
pub struct SettingsChanged {
    pub settings: Settings,
}

#[event]
pub struct ContestCreated {
    pub contest: Pubkey,
    pub sponsor: Pubkey,
    pub mode: Mode,
    pub pool: u64,
    pub budget: u64,
    pub only: Pubkey,
    pub play_until: i64,
}

#[event]
pub struct Registered {
    pub contest: Pubkey,
    pub sgt_mint: Pubkey,
    pub index: u32,
}

#[event]
pub struct DrawRequested {
    pub contest: Pubkey,
    pub seed: [u8; 32],
}

#[event]
pub struct Drawn {
    pub contest: Pubkey,
    pub entered: u32,
    pub selected: u32,
    pub budget: u64,
    pub randomness: [u8; 32],
}

#[event]
pub struct Unlocked {
    pub contest: Pubkey,
    pub entry: Pubkey,
    pub sgt_mint: Pubkey,
    pub owner: Pubkey,
    pub owner_paid: u64,
    pub guest_share: u64,
    pub roster_size: u8,
    pub roster_root: [u8; 32],
    pub result: [u8; 32],
}

#[event]
pub struct Claimed {
    pub contest: Pubkey,
    pub entry: Pubkey,
    pub index: u8,
    pub claimer: Pubkey,
    pub recipient: Pubkey,
    pub amount: u64,
}

#[event]
pub struct TestTokenMinted {
    pub wallet: Pubkey,
    pub mint: Pubkey,
}

#[event]
pub struct ContestClosed {
    pub contest: Pubkey,
    pub returned: u64,
}

#[error_code]
pub enum VaultError {
    #[msg("Amount must be greater than zero")]
    ZeroAmount,
    #[msg("Settings are out of bounds")]
    BadSettings,
    #[msg("New contests are paused")]
    Paused,
    #[msg("This kind of contest isn't offered")]
    ModeNotAllowed,
    #[msg("The contest's terms don't fit its mode")]
    BadMode,
    #[msg("The share is out of range")]
    BadShare,
    #[msg("Wins per Genesis Token are out of range")]
    BadWins,
    #[msg("The contest's windows are out of range")]
    BadWindow,
    #[msg("The budget must pay an owner and a guest and fit the pool")]
    BadBudget,
    #[msg("The pool is too small")]
    PoolTooSmall,
    #[msg("Not a Seeker Genesis Token held by the signer")]
    NotASeeker,
    #[msg("This contest is for another Seeker")]
    NotThisSeeker,
    #[msg("Entry has closed")]
    EntryClosed,
    #[msg("It isn't time for the draw")]
    NotDrawTime,
    #[msg("Nobody entered")]
    NoEntries,
    #[msg("The draw is done")]
    AlreadyDrawn,
    #[msg("The randomness request is still pending")]
    DrawPending,
    #[msg("That isn't this contest's randomness")]
    BadRandomness,
    #[msg("This entry wasn't selected")]
    NotSelected,
    #[msg("Already unlocked")]
    AlreadyUnlocked,
    #[msg("The pool is spent")]
    PoolSpent,
    #[msg("The contest has ended")]
    Expired,
    #[msg("The roster doesn't fit this contest")]
    RosterSize,
    #[msg("The entry is not unlocked")]
    NotUnlocked,
    #[msg("The claim window has closed")]
    ClaimWindowClosed,
    #[msg("Not in the roster")]
    NotInRoster,
    #[msg("Already claimed")]
    AlreadyClaimed,
    #[msg("This key has claimed its limit from this contest")]
    ClaimLimit,
    #[msg("Proof too long")]
    ProofTooLong,
    #[msg("Not closable yet")]
    NotClosable,
    #[msg("Not allowed")]
    Unauthorized,
    #[msg("Wrong mint")]
    WrongMint,
    #[msg("Arithmetic overflow")]
    Overflow,
    #[msg("Test tokens are off on this network")]
    TestTokensOff,
}
