use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, CloseAccount, Mint, TokenAccount, TokenInterface, TransferChecked};

declare_id!("3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW");

pub const CONFIG_SEED: &[u8] = b"config";
pub const OPPORTUNITY_SEED: &[u8] = b"opportunity";
pub const BPS: u64 = 10_000;
pub const MIN_PLAYERS: u8 = 2;
pub const MAX_PLAYERS: u8 = 32;
/// Helpers fit a u64 bitmap of who has claimed.
pub const MAX_HELPERS: u8 = 64;
pub const MAX_PROOF: usize = 7;
pub const MAX_DIFFICULTY: u8 = 3;
pub const CLAIM_WINDOW: i64 = 30 * 24 * 60 * 60;

pub const TOKEN_2022: Pubkey = Pubkey::from_str_const("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
const TOKEN_GROUP_MEMBER: u16 = 23;
/// Token-2022 pads extended mints to an account's length and puts the account type after it.
const ACCOUNT_TYPE_OFFSET: usize = 165;
const ACCOUNT_TYPE_MINT: u8 = 1;

const LEAF: u8 = 0;
pub const UNBOUND: [u8; 32] = [0; 32];
const NODE: u8 = 1;

#[program]
pub mod formation_vault {
    use super::*;

    pub fn init_config(ctx: Context<InitConfig>, sgt_group: Pubkey) -> Result<()> {
        ctx.accounts.config.set_inner(Config {
            admin: ctx.accounts.admin.key(),
            mint: ctx.accounts.mint.key(),
            sgt_group,
            bump: ctx.bumps.config,
        });
        Ok(())
    }

    pub fn set_sgt_group(ctx: Context<AdminOnly>, sgt_group: Pubkey) -> Result<()> {
        ctx.accounts.config.sgt_group = sgt_group;
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub fn create(
        ctx: Context<Create>,
        id: [u8; 16],
        amount: u64,
        players: u8,
        owner_bps: u16,
        challenge: u16,
        difficulty: u8,
        title: [u8; 32],
        expires_at: i64,
    ) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        require!(amount > 0, VaultError::ZeroAmount);
        require!((MIN_PLAYERS..=MAX_PLAYERS).contains(&players), VaultError::BadPlayers);
        require!((owner_bps as u64) < BPS, VaultError::BadShare);
        require!(difficulty <= MAX_DIFFICULTY, VaultError::BadDifficulty);
        require!(expires_at > now, VaultError::AlreadyExpired);
        verify_sgt(&ctx.accounts.sgt_mint, &ctx.accounts.config.sgt_group)?;

        let a = &ctx.accounts;
        let received = deposit(&a.sponsor_token, &a.vault, &a.mint, &a.sponsor, &a.token_program, amount)?;

        ctx.accounts.opportunity.set_inner(Opportunity {
            id,
            sponsor: ctx.accounts.sponsor.key(),
            seeker: ctx.accounts.seeker.key(),
            sgt: ctx.accounts.sgt_mint.key(),
            mint: ctx.accounts.mint.key(),
            vault: ctx.accounts.vault.key(),
            amount: received,
            players,
            owner_bps,
            challenge,
            difficulty,
            title,
            created_at: now,
            expires_at,
            state: OpportunityState::Open,
            roster_root: [0; 32],
            roster_size: 0,
            helper_share: 0,
            claimed: 0,
            result: [0; 32],
            unlocked_at: 0,
            bump: ctx.bumps.opportunity,
        });
        emit!(Created {
            opportunity: ctx.accounts.opportunity.key(),
            seeker: ctx.accounts.seeker.key(),
            sgt: ctx.accounts.sgt_mint.key(),
            amount: received,
            players,
            challenge,
            expires_at,
        });
        Ok(())
    }

    pub fn unlock(ctx: Context<Unlock>, roster_root: [u8; 32], roster_size: u8, result: [u8; 32]) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let o = &ctx.accounts.opportunity;
        require!(o.state == OpportunityState::Open, VaultError::NotOpen);
        require!(now < o.expires_at, VaultError::Expired);
        require!(roster_size >= o.players - 1 && roster_size <= MAX_HELPERS, VaultError::RosterSize);

        let (owner, share) = split(o.amount, o.owner_bps, roster_size)?;
        let seeds: &[&[u8]] = &[OPPORTUNITY_SEED, &o.id, &[o.bump]];
        pay_out(&ctx.accounts.vault, &ctx.accounts.seeker_token, &ctx.accounts.mint, &ctx.accounts.opportunity, &ctx.accounts.token_program, seeds, owner)?;

        let o = &mut ctx.accounts.opportunity;
        o.state = OpportunityState::Unlocked;
        o.roster_root = roster_root;
        o.roster_size = roster_size;
        o.helper_share = share;
        o.result = result;
        o.unlocked_at = now;
        emit!(Unlocked { opportunity: o.key(), owner_paid: owner, helper_share: share, roster_size, roster_root, result });
        Ok(())
    }

    pub fn claim(ctx: Context<Claim>, index: u8, proof: Vec<[u8; 32]>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let o = &ctx.accounts.opportunity;
        require!(o.state == OpportunityState::Unlocked, VaultError::NotUnlocked);
        require!(now < o.unlocked_at.checked_add(CLAIM_WINDOW).ok_or(VaultError::Overflow)?, VaultError::ClaimWindowClosed);
        require!(index < o.roster_size, VaultError::NotInRoster);
        let bit = 1u64 << index;
        require!(o.claimed & bit == 0, VaultError::AlreadyClaimed);
        require!(proof.len() <= MAX_PROOF, VaultError::ProofTooLong);
        // A helper who named a wallet at the seal is paid there by whoever cranks the claim; one who
        // didn't must sign with their claim key and may send the share anywhere.
        let claimer = ctx.accounts.claimer.key();
        let recipient = ctx.accounts.recipient.key();
        let bound = roster_root(index, &claimer, &recipient.to_bytes(), &proof) == o.roster_root;
        let open = ctx.accounts.claimer.is_signer && roster_root(index, &claimer, &UNBOUND, &proof) == o.roster_root;
        require!(bound || open, VaultError::NotInRoster);

        let amount = o.helper_share;
        let seeds: &[&[u8]] = &[OPPORTUNITY_SEED, &o.id, &[o.bump]];
        pay_out(&ctx.accounts.vault, &ctx.accounts.recipient_token, &ctx.accounts.mint, &ctx.accounts.opportunity, &ctx.accounts.token_program, seeds, amount)?;

        let o = &mut ctx.accounts.opportunity;
        o.claimed |= bit;
        emit!(Claimed { opportunity: o.key(), index, claimer: ctx.accounts.claimer.key(), recipient: ctx.accounts.recipient.key(), amount });
        Ok(())
    }

    pub fn close(ctx: Context<Close>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let o = &ctx.accounts.opportunity;
        let expired = o.state == OpportunityState::Open && now >= o.expires_at;
        let settled = o.state == OpportunityState::Unlocked
            && (o.claimed.count_ones() as u8 == o.roster_size || now >= o.unlocked_at.checked_add(CLAIM_WINDOW).ok_or(VaultError::Overflow)?);
        require!(expired || settled, VaultError::NotClosable);

        let seeds: &[&[u8]] = &[OPPORTUNITY_SEED, &o.id, &[o.bump]];
        let rest = ctx.accounts.vault.amount;
        pay_out(&ctx.accounts.vault, &ctx.accounts.sponsor_token, &ctx.accounts.mint, &ctx.accounts.opportunity, &ctx.accounts.token_program, seeds, rest)?;
        token_interface::close_account(CpiContext::new_with_signer(
            ctx.accounts.token_program.key(),
            CloseAccount {
                account: ctx.accounts.vault.to_account_info(),
                destination: ctx.accounts.sponsor.to_account_info(),
                authority: ctx.accounts.opportunity.to_account_info(),
            },
            &[seeds],
        ))?;
        emit!(Closed { opportunity: ctx.accounts.opportunity.key(), returned: rest });
        Ok(())
    }
}

pub fn split(amount: u64, owner_bps: u16, helpers: u8) -> Result<(u64, u64)> {
    require!(helpers > 0, VaultError::RosterSize);
    let cut = u64::try_from(amount as u128 * owner_bps as u128 / BPS as u128).map_err(|_| error!(VaultError::Overflow))?;
    let pool = amount.checked_sub(cut).ok_or(VaultError::Overflow)?;
    let share = pool / helpers as u64;
    let dust = pool - share * helpers as u64;
    Ok((cut + dust, share))
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
    // Measure what arrived, so a mint with transfer fees cannot overstate the pot.
    let after = TokenAccount::try_deserialize(&mut &vault.to_account_info().data.borrow()[..])?.amount;
    let received = after.checked_sub(before).ok_or(VaultError::Overflow)?;
    require!(received > 0, VaultError::ZeroAmount);
    Ok(received)
}

fn pay_out<'info>(
    vault: &InterfaceAccount<'info, TokenAccount>,
    to: &InterfaceAccount<'info, TokenAccount>,
    mint: &InterfaceAccount<'info, Mint>,
    opportunity: &Account<'info, Opportunity>,
    token_program: &Interface<'info, TokenInterface>,
    seeds: &[&[u8]],
    amount: u64,
) -> Result<()> {
    if amount == 0 {
        return Ok(());
    }
    token_interface::transfer_checked(
        CpiContext::new_with_signer(
            token_program.key(),
            TransferChecked {
                from: vault.to_account_info(),
                mint: mint.to_account_info(),
                to: to.to_account_info(),
                authority: opportunity.to_account_info(),
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
#[instruction(id: [u8; 16])]
pub struct Create<'info> {
    #[account(mut)]
    pub sponsor: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump, has_one = mint @ VaultError::WrongMint)]
    pub config: Box<Account<'info, Config>>,
    /// CHECK: the Seeker owner; bound by holding `sgt_account`.
    pub seeker: UncheckedAccount<'info>,
    /// CHECK: checked in the handler to be a Seeker Genesis Token.
    pub sgt_mint: UncheckedAccount<'info>,
    #[account(
        constraint = *sgt_account.to_account_info().owner == TOKEN_2022 @ VaultError::NotASeeker,
        constraint = sgt_account.mint == sgt_mint.key() @ VaultError::NotASeeker,
        constraint = sgt_account.owner == seeker.key() @ VaultError::NotASeeker,
        constraint = sgt_account.amount == 1 @ VaultError::NotASeeker,
    )]
    pub sgt_account: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(init, payer = sponsor, space = 8 + Opportunity::INIT_SPACE, seeds = [OPPORTUNITY_SEED, id.as_ref()], bump)]
    pub opportunity: Box<Account<'info, Opportunity>>,
    #[account(
        init,
        payer = sponsor,
        associated_token::mint = mint,
        associated_token::authority = opportunity,
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
pub struct Unlock<'info> {
    #[account(mut)]
    pub seeker: Signer<'info>,
    #[account(
        mut,
        seeds = [OPPORTUNITY_SEED, opportunity.id.as_ref()],
        bump = opportunity.bump,
        has_one = seeker @ VaultError::Unauthorized,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub opportunity: Box<Account<'info, Opportunity>>,
    #[account(mut)]
    pub vault: Box<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init_if_needed,
        payer = seeker,
        associated_token::mint = mint,
        associated_token::authority = seeker,
        associated_token::token_program = token_program,
    )]
    pub seeker_token: Box<InterfaceAccount<'info, TokenAccount>>,
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
        mut,
        seeds = [OPPORTUNITY_SEED, opportunity.id.as_ref()],
        bump = opportunity.bump,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub opportunity: Box<Account<'info, Opportunity>>,
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
pub struct Close<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: bound to the opportunity by `has_one`; receives the rent and what is left.
    #[account(mut)]
    pub sponsor: UncheckedAccount<'info>,
    #[account(
        mut,
        close = sponsor,
        seeds = [OPPORTUNITY_SEED, opportunity.id.as_ref()],
        bump = opportunity.bump,
        has_one = sponsor,
        has_one = vault,
        has_one = mint @ VaultError::WrongMint,
    )]
    pub opportunity: Box<Account<'info, Opportunity>>,
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

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub mint: Pubkey,
    pub sgt_group: Pubkey,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Opportunity {
    pub id: [u8; 16],
    pub sponsor: Pubkey,
    pub seeker: Pubkey,
    pub sgt: Pubkey,
    pub mint: Pubkey,
    pub vault: Pubkey,
    pub amount: u64,
    pub players: u8,
    pub owner_bps: u16,
    pub challenge: u16,
    pub difficulty: u8,
    pub title: [u8; 32],
    pub created_at: i64,
    pub expires_at: i64,
    pub state: OpportunityState,
    pub roster_root: [u8; 32],
    pub roster_size: u8,
    pub helper_share: u64,
    /// Bit i is set once helper i has claimed.
    pub claimed: u64,
    pub result: [u8; 32],
    pub unlocked_at: i64,
    pub bump: u8,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum OpportunityState {
    Open,
    Unlocked,
}

#[event]
pub struct Created {
    pub opportunity: Pubkey,
    pub seeker: Pubkey,
    pub sgt: Pubkey,
    pub amount: u64,
    pub players: u8,
    pub challenge: u16,
    pub expires_at: i64,
}

#[event]
pub struct Unlocked {
    pub opportunity: Pubkey,
    pub owner_paid: u64,
    pub helper_share: u64,
    pub roster_size: u8,
    pub roster_root: [u8; 32],
    pub result: [u8; 32],
}

#[event]
pub struct Claimed {
    pub opportunity: Pubkey,
    pub index: u8,
    pub claimer: Pubkey,
    pub recipient: Pubkey,
    pub amount: u64,
}

#[event]
pub struct Closed {
    pub opportunity: Pubkey,
    pub returned: u64,
}

#[error_code]
pub enum VaultError {
    #[msg("Amount must be greater than zero")]
    ZeroAmount,
    #[msg("A Formation takes between 2 and 32 players")]
    BadPlayers,
    #[msg("The owner's share must leave something for the helpers")]
    BadShare,
    #[msg("Unknown difficulty")]
    BadDifficulty,
    #[msg("The opportunity would already be expired")]
    AlreadyExpired,
    #[msg("Not a Seeker Genesis Token held by the Seeker")]
    NotASeeker,
    #[msg("The opportunity is not open")]
    NotOpen,
    #[msg("The opportunity has expired")]
    Expired,
    #[msg("The roster doesn't fit this opportunity")]
    RosterSize,
    #[msg("The reward is not unlocked")]
    NotUnlocked,
    #[msg("The claim window has closed")]
    ClaimWindowClosed,
    #[msg("Not in the roster")]
    NotInRoster,
    #[msg("Already claimed")]
    AlreadyClaimed,
    #[msg("Proof too long")]
    ProofTooLong,
    #[msg("The opportunity can't be closed yet")]
    NotClosable,
    #[msg("Not allowed")]
    Unauthorized,
    #[msg("Wrong mint")]
    WrongMint,
    #[msg("Arithmetic overflow")]
    Overflow,
}
