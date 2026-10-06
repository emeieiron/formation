use anchor_lang::prelude::Pubkey;
use anchor_lang::solana_program::program_pack::Pack;
use anchor_lang::{AccountDeserialize, InstructionData, ToAccountMetas};
use anchor_spl::associated_token::{self, get_associated_token_address, get_associated_token_address_with_program_id};
use anchor_spl::token::spl_token;
use formation_vault::{
    accounts, instruction, permute, split, Config, Contest, Entry, EntryState, Mode, Receipt, Settings, VaultError, CONFIG_SEED,
    CONTEST_SEED, ENTRY_SEED, MODE_DRAW, MODE_FIRST_COME, RECEIPT_SEED, TEST_AUTHORITY_SEED, TEST_TOKEN_SEED, TOKEN_2022, UNBOUND,
    VRF_NETWORK_SEED, VRF_REQUEST_SEED,
};
use litesvm::LiteSVM;
use solana_account::Account;
use solana_address::Address;
use solana_clock::Clock;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::{Message, VersionedMessage};
use solana_sha256_hasher::{hash, hashv};
use solana_signer::Signer;
use solana_transaction::versioned::VersionedTransaction;

fn addr(p: &Pubkey) -> Address {
    Address::new_from_array(p.to_bytes())
}
fn pk(a: &Address) -> Pubkey {
    Pubkey::new_from_array(a.to_bytes())
}

const DECIMALS: u8 = 6;
const SKR: u64 = 1_000_000;
const NOW: i64 = 1_800_000_000;
const HOUR: i64 = 3_600;
const DAY: i64 = 24 * HOUR;
const CLAIM_WINDOW: i64 = 30 * DAY;
const RESULT: [u8; 32] = [7; 32];

const GROUP_POINTER: u16 = 20;
const TOKEN_GROUP: u16 = 21;
const GROUP_MEMBER_POINTER: u16 = 22;
const TOKEN_GROUP_MEMBER: u16 = 23;
const VRF: Pubkey = Pubkey::from_str_const("VRFzZoJdhFWL8rkvu87LpKM3RbcVezpMEc6X5GVDr7y");

fn defaults() -> Settings {
    Settings {
        paused: false,
        modes: MODE_FIRST_COME | MODE_DRAW,
        owner_weight: 3,
        min_guest_share: 10 * SKR,
        min_budgets: 10,
        max_per_key: 3,
        max_wins_per_sgt: 3,
        guest_ceiling: 64,
        claim_window: CLAIM_WINDOW,
        min_play_window: HOUR,
        max_play_window: 90 * DAY,
        min_enter_window: HOUR,
        max_enter_window: 30 * DAY,
        draw_timeout: HOUR,
        test_tokens: false,
    }
}

/// Mirrors the app's RosterTree.
struct Roster {
    levels: Vec<Vec<[u8; 32]>>,
}

impl Roster {
    fn new(keys: &[Pubkey]) -> Self {
        Self::with_wallets(&keys.iter().map(|k| (*k, UNBOUND)).collect::<Vec<_>>())
    }

    fn of(guests: &[Keypair]) -> Self {
        Self::new(&guests.iter().map(wallet).collect::<Vec<_>>())
    }

    fn with_wallets(entries: &[(Pubkey, [u8; 32])]) -> Self {
        let mut level: Vec<[u8; 32]> = entries.iter().enumerate().map(|(i, (k, w))| leaf(i as u8, k, w)).collect();
        let mut levels = vec![level.clone()];
        while level.len() > 1 {
            level = level.chunks(2).map(|c| if c.len() == 2 { node(&c[0], &c[1]) } else { c[0] }).collect();
            levels.push(level.clone());
        }
        Roster { levels }
    }

    fn root(&self) -> [u8; 32] {
        self.levels.last().unwrap()[0]
    }

    fn proof(&self, index: usize) -> Vec<[u8; 32]> {
        let mut at = index;
        let mut proof = vec![];
        for level in &self.levels[..self.levels.len() - 1] {
            if let Some(sibling) = level.get(at ^ 1) {
                proof.push(*sibling);
            }
            at /= 2;
        }
        proof
    }
}

fn leaf(index: u8, key: &Pubkey, wallet: &[u8; 32]) -> [u8; 32] {
    hashv(&[&[0], &[index], key.as_ref(), wallet]).to_bytes()
}

fn node(a: &[u8; 32], b: &[u8; 32]) -> [u8; 32] {
    let (lo, hi) = if a <= b { (a, b) } else { (b, a) };
    hashv(&[&[1], lo, hi]).to_bytes()
}

/// The keys the app's reference vectors use: key i is 32 bytes of i + 1.
fn reference_keys(n: usize) -> Vec<Pubkey> {
    (0..n).map(|i| Pubkey::new_from_array([i as u8 + 1; 32])).collect()
}

fn guests(n: usize) -> Vec<Keypair> {
    (0..n).map(|_| Keypair::new()).collect()
}

struct Seeker {
    wallet: Keypair,
    sgt: Pubkey,
    sgt_account: Pubkey,
}

impl Seeker {
    fn key(&self) -> Pubkey {
        wallet(&self.wallet)
    }
}

#[derive(Clone)]
struct Terms {
    nonce: u64,
    amount: u64,
    mode: Mode,
    budget: u64,
    only: Pubkey,
    wins: u8,
    enter_until: i64,
    play_until: i64,
    mint: Pubkey,
}

struct Env {
    svm: LiteSVM,
    admin: Keypair,
    sponsor: Keypair,
    seeker: Seeker,
    mint: Pubkey,
    group: Pubkey,
    nonce: u64,
}

fn program_id() -> Pubkey {
    formation_vault::ID
}

fn config_pda() -> Pubkey {
    Pubkey::find_program_address(&[CONFIG_SEED], &program_id()).0
}

fn entry_pda(contest: &Pubkey, sgt: &Pubkey, round: u8) -> Pubkey {
    Pubkey::find_program_address(&[ENTRY_SEED, contest.as_ref(), sgt.as_ref(), &[round]], &program_id()).0
}

fn receipt_pda(contest: &Pubkey, claim_key: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[RECEIPT_SEED, contest.as_ref(), claim_key.as_ref()], &program_id()).0
}

fn request_pda(seed: &[u8; 32]) -> Pubkey {
    Pubkey::find_program_address(&[VRF_REQUEST_SEED, seed], &VRF).0
}

fn network_state() -> Pubkey {
    Pubkey::find_program_address(&[VRF_NETWORK_SEED], &VRF).0
}

/// ORAO's network state holds its authority, then its treasury.
fn treasury() -> Pubkey {
    Pubkey::new_from_array(include_bytes!("fixtures/orao-network-state.bin")[40..72].try_into().unwrap())
}

fn wallet_of(k: &Keypair) -> Pubkey {
    wallet(k)
}

fn wallet(k: &Keypair) -> Pubkey {
    pk(&k.pubkey())
}

impl Env {
    fn new() -> Self {
        let mut env = Self::without_config();
        env.init_config(&env.admin.insecure_clone(), defaults()).unwrap();
        env
    }

    fn without_config() -> Self {
        let mut svm = LiteSVM::new();
        let admin = Keypair::new();
        let sponsor = Keypair::new();
        for k in [&admin, &sponsor] {
            svm.airdrop(&k.pubkey(), 10_000_000_000).unwrap();
        }
        svm.add_program(addr(&program_id()), include_bytes!("../../target/deploy/formation_vault.so")).unwrap();
        svm.add_program(addr(&VRF), include_bytes!("fixtures/orao-vrf.so")).unwrap();
        put(&mut svm, &network_state(), include_bytes!("fixtures/orao-network-state.bin").to_vec(), &VRF);
        svm.airdrop(&addr(&treasury()), 1_000_000_000).unwrap();
        set_upgrade_authority(&mut svm, &wallet(&admin));
        set_time(&mut svm, NOW);

        let mint = Pubkey::new_unique();
        put(&mut svm, &mint, skr_mint(), &spl_token::ID);

        let group = Pubkey::new_unique();
        let placeholder = Seeker { wallet: Keypair::new(), sgt: Pubkey::default(), sgt_account: Pubkey::default() };
        let mut env = Env { svm, admin, sponsor, seeker: placeholder, mint, group, nonce: 0 };
        env.seeker = env.new_seeker();
        env.give(&wallet(&env.sponsor), 100_000 * SKR);
        env
    }

    fn new_seeker(&mut self) -> Seeker {
        let wallet = self.user();
        let sgt = Pubkey::new_unique();
        put(&mut self.svm, &sgt, sgt_mint(&sgt, &sgt, &self.group), &TOKEN_2022);
        let sgt_account = self.token_account(&sgt, &pk(&wallet.pubkey()), 1, &TOKEN_2022);
        Seeker { wallet, sgt, sgt_account }
    }

    /// Moves the Seeker's token to a fresh wallet, emptying the old account.
    fn move_sgt(&mut self, from: &Seeker) -> Seeker {
        put(&mut self.svm, &from.sgt_account, token_account(&from.sgt, &from.key(), 0), &TOKEN_2022);
        let wallet = self.user();
        let sgt_account = self.token_account(&from.sgt, &pk(&wallet.pubkey()), 1, &TOKEN_2022);
        Seeker { wallet, sgt: from.sgt, sgt_account }
    }

    fn user(&mut self) -> Keypair {
        let k = Keypair::new();
        self.svm.airdrop(&k.pubkey(), 1_000_000_000).unwrap();
        k
    }

    fn token_account(&mut self, mint: &Pubkey, owner: &Pubkey, amount: u64, program: &Pubkey) -> Pubkey {
        let key = Pubkey::new_unique();
        put(&mut self.svm, &key, token_account(mint, owner, amount), program);
        key
    }

    fn give(&mut self, owner: &Pubkey, amount: u64) {
        let ata = get_associated_token_address(owner, &self.mint);
        put(&mut self.svm, &ata, token_account(&self.mint, owner, amount), &spl_token::ID);
    }

    fn balance(&self, owner: &Pubkey) -> u64 {
        self.token_balance(&get_associated_token_address(owner, &self.mint))
    }

    fn token_balance(&self, account: &Pubkey) -> u64 {
        match self.svm.get_account(&addr(account)) {
            Some(a) if !a.data.is_empty() => spl_token::state::Account::unpack(&a.data).unwrap().amount,
            _ => 0,
        }
    }

    fn vault(&self, contest: &Pubkey) -> Pubkey {
        get_associated_token_address(contest, &self.mint)
    }

    fn lamports(&self, key: &Pubkey) -> u64 {
        self.svm.get_account(&addr(key)).map_or(0, |a| a.lamports)
    }

    fn exists(&self, key: &Pubkey) -> bool {
        self.lamports(key) > 0
    }

    fn contest(&self, key: &Pubkey) -> Contest {
        read(&self.svm, key)
    }

    fn entry(&self, contest: &Pubkey, sgt: &Pubkey, round: u8) -> Entry {
        read(&self.svm, &entry_pda(contest, sgt, round))
    }

    fn send(&mut self, ix: Instruction, signers: &[&Keypair]) -> Result<(), String> {
        self.send_all(&[ix], signers).map(|_| ())
    }

    fn send_all(&mut self, ixs: &[Instruction], signers: &[&Keypair]) -> Result<usize, String> {
        self.svm.expire_blockhash();
        let msg = Message::new_with_blockhash(ixs, Some(&signers[0].pubkey()), &self.svm.latest_blockhash());
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(msg), signers).unwrap();
        let size = 1 + tx.signatures.len() * 64 + tx.message.serialize().len();
        self.svm.send_transaction(tx).map(|_| size).map_err(|e| format!("{:?}", e.err))
    }

    fn ix(&self, data: impl InstructionData, accounts: impl ToAccountMetas) -> Instruction {
        Instruction {
            program_id: addr(&program_id()),
            accounts: accounts
                .to_account_metas(None)
                .into_iter()
                .map(|m| AccountMeta { pubkey: addr(&m.pubkey), is_signer: m.is_signer, is_writable: m.is_writable })
                .collect(),
            data: data.data(),
        }
    }

    fn init_config(&mut self, signer: &Keypair, settings: Settings) -> Result<(), String> {
        let program_data = Pubkey::find_program_address(&[program_id().as_ref()], &bpf_loader_upgradeable()).0;
        let ix = self.ix(
            instruction::InitConfig { sgt_group: self.group, vrf_program: VRF, settings },
            accounts::InitConfig {
                admin: wallet(signer),
                config: config_pda(),
                mint: self.mint,
                program: program_id(),
                program_data,
                system_program: system_program(),
            },
        );
        self.send(ix, &[signer])
    }

    fn admin(&mut self, signer: &Keypair, data: impl InstructionData) -> Result<(), String> {
        let ix = self.ix(data, accounts::AdminOnly { admin: wallet(signer), config: config_pda() });
        self.send(ix, &[signer])
    }

    fn set_settings(&mut self, change: impl FnOnce(&mut Settings)) {
        let mut settings = read::<Config>(&self.svm, &config_pda()).settings;
        change(&mut settings);
        let admin = self.admin.insecure_clone();
        self.admin(&admin, instruction::SetSettings { settings }).unwrap();
    }

    fn first_come(&mut self, amount: u64, budget: u64) -> Terms {
        self.nonce += 1;
        Terms {
            nonce: self.nonce,
            amount,
            mode: Mode::FirstCome,
            budget,
            only: Pubkey::default(),
            wins: 1,
            enter_until: 0,
            play_until: NOW + DAY,
            mint: self.mint,
        }
    }

    fn draw(&mut self, amount: u64, bps: u16) -> Terms {
        Terms { mode: Mode::Draw { bps }, budget: 0, enter_until: NOW + DAY, play_until: NOW + 2 * DAY, ..self.first_come(amount, 0) }
    }

    fn contest_key(&self, terms: &Terms) -> Pubkey {
        Pubkey::find_program_address(&[CONTEST_SEED, wallet(&self.sponsor).as_ref(), &terms.nonce.to_le_bytes()], &program_id()).0
    }

    fn create(&mut self, terms: &Terms) -> Result<Pubkey, String> {
        let sponsor = wallet(&self.sponsor);
        let contest = self.contest_key(terms);
        let ix = self.ix(
            instruction::CreateContest {
                nonce: terms.nonce,
                amount: terms.amount,
                mode: terms.mode,
                budget: terms.budget,
                only: terms.only,
                wins_per_sgt: terms.wins,
                enter_until: terms.enter_until,
                play_until: terms.play_until,
                title: title("Genesis Rally"),
                branding: [9; 32],
            },
            accounts::CreateContest {
                sponsor,
                config: config_pda(),
                contest,
                vault: get_associated_token_address(&contest, &terms.mint),
                sponsor_token: get_associated_token_address(&sponsor, &terms.mint),
                mint: terms.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        );
        let sponsor = self.sponsor.insecure_clone();
        self.send(ix, &[&sponsor]).map(|_| contest)
    }

    fn unlock(&mut self, seeker: &Seeker, contest: &Pubkey, round: u8, roster: &Roster, size: u8) -> Result<(), String> {
        let ix = self.unlock_ix(seeker, contest, round, roster.root(), size);
        self.send(ix, &[&seeker.wallet])
    }

    fn unlock_ix(&self, seeker: &Seeker, contest: &Pubkey, round: u8, roster_root: [u8; 32], roster_size: u8) -> Instruction {
        let owner = seeker.key();
        self.ix(
            instruction::Unlock { round, roster_root, roster_size, result: RESULT },
            accounts::Unlock {
                owner,
                contest: *contest,
                sgt_mint: seeker.sgt,
                sgt_account: seeker.sgt_account,
                entry: entry_pda(contest, &seeker.sgt, round),
                vault: self.vault(contest),
                owner_token: get_associated_token_address(&owner, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        )
    }

    fn unlock_drawn(&mut self, seeker: &Seeker, contest: &Pubkey, roster: &Roster, size: u8) -> Result<(), String> {
        let owner = seeker.key();
        let ix = self.ix(
            instruction::UnlockDrawn { roster_root: roster.root(), roster_size: size, result: RESULT },
            accounts::UnlockDrawn {
                owner,
                contest: *contest,
                sgt_mint: seeker.sgt,
                sgt_account: seeker.sgt_account,
                entry: entry_pda(contest, &seeker.sgt, 0),
                vault: self.vault(contest),
                owner_token: get_associated_token_address(&owner, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        );
        self.send(ix, &[&seeker.wallet])
    }

    fn register(&mut self, seeker: &Seeker, contest: &Pubkey) -> Result<(), String> {
        let ix = self.ix(
            instruction::Register {},
            accounts::Register {
                owner: seeker.key(),
                contest: *contest,
                sgt_mint: seeker.sgt,
                sgt_account: seeker.sgt_account,
                entry: entry_pda(contest, &seeker.sgt, 0),
                system_program: system_program(),
            },
        );
        self.send(ix, &[&seeker.wallet])
    }

    fn next_seed(&self, contest: &Pubkey) -> [u8; 32] {
        hashv(&[b"formation.draw", contest.as_ref(), &[self.contest(contest).draw.attempts]]).to_bytes()
    }

    fn request_draw(&mut self, contest: &Pubkey) -> Result<[u8; 32], String> {
        let payer = self.user();
        let c = self.contest(contest);
        let seed = self.next_seed(contest);
        let previous = if c.draw.attempts > 0 { request_pda(&c.draw.seed) } else { system_program() };
        let ix = self.ix(
            instruction::RequestDraw {},
            accounts::RequestDraw {
                payer: wallet(&payer),
                contest: *contest,
                vrf_program: VRF,
                network_state: network_state(),
                treasury: treasury(),
                request: request_pda(&seed),
                previous,
                system_program: system_program(),
            },
        );
        self.send(ix, &[&payer]).map(|_| seed)
    }

    /// Writes what ORAO's fulfilled request looks like once its authorities answer.
    fn fulfill(&mut self, seed: &[u8; 32], randomness: [u8; 64]) {
        let mut data = hash(b"account:RandomnessV2").to_bytes()[..8].to_vec();
        data.push(1);
        data.extend(Pubkey::new_unique().to_bytes());
        data.extend(seed);
        data.extend(randomness);
        put(&mut self.svm, &request_pda(seed), data, &VRF);
    }

    fn finalize(&mut self, contest: &Pubkey, request: &Pubkey) -> Result<(), String> {
        let payer = self.user();
        let ix = self.ix(instruction::FinalizeDraw {}, accounts::FinalizeDraw { contest: *contest, request: *request });
        self.send(ix, &[&payer])
    }

    /// Runs a whole draw over freshly registered Seekers and returns them.
    fn drawn(&mut self, terms: &Terms, entrants: usize) -> (Pubkey, Vec<Seeker>) {
        let contest = self.create(terms).unwrap();
        let seekers: Vec<Seeker> = (0..entrants).map(|_| self.new_seeker()).collect();
        for s in &seekers {
            self.register(s, &contest).unwrap();
        }
        set_time(&mut self.svm, terms.enter_until);
        let seed = self.request_draw(&contest).unwrap();
        self.fulfill(&seed, [5; 64]);
        self.finalize(&contest, &request_pda(&seed)).unwrap();
        (contest, seekers)
    }

    #[allow(clippy::too_many_arguments)]
    fn claim(&mut self, payer: &Keypair, claimer: &Keypair, recipient: &Pubkey, contest: &Pubkey, entry: &Pubkey, index: u8, proof: Vec<[u8; 32]>) -> Result<(), String> {
        let mut ix = self.claim_ix(payer, &wallet(claimer), recipient, contest, entry, index, proof);
        ix.accounts[1].is_signer = true;
        self.send(ix, &[payer, claimer])
    }

    #[allow(clippy::too_many_arguments)]
    fn crank(&mut self, payer: &Keypair, claimer: &Pubkey, recipient: &Pubkey, contest: &Pubkey, entry: &Pubkey, index: u8, proof: Vec<[u8; 32]>) -> Result<(), String> {
        let ix = self.claim_ix(payer, claimer, recipient, contest, entry, index, proof);
        self.send(ix, &[payer])
    }

    #[allow(clippy::too_many_arguments)]
    fn claim_ix(&self, payer: &Keypair, claimer: &Pubkey, recipient: &Pubkey, contest: &Pubkey, entry: &Pubkey, index: u8, proof: Vec<[u8; 32]>) -> Instruction {
        self.ix(
            instruction::Claim { index, proof },
            accounts::Claim {
                payer: wallet(payer),
                claimer: *claimer,
                recipient: *recipient,
                contest: *contest,
                entry: *entry,
                receipt: receipt_pda(contest, claimer),
                vault: self.vault(contest),
                recipient_token: get_associated_token_address(recipient, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        )
    }

    fn mint_test_token(&mut self, wallet: &Pubkey) -> Result<Seeker, String> {
        let payer = self.user();
        let mint = Pubkey::find_program_address(&[TEST_TOKEN_SEED, wallet.as_ref()], &program_id()).0;
        let token_account = get_associated_token_address_with_program_id(wallet, &mint, &TOKEN_2022);
        let ix = self.ix(
            instruction::MintTestToken {},
            accounts::MintTestToken {
                payer: wallet_of(&payer),
                wallet: *wallet,
                config: config_pda(),
                authority: test_authority(),
                group: self.group,
                mint,
                token_account,
                token_program: TOKEN_2022,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        );
        self.send(ix, &[&payer]).map(|_| Seeker { wallet: Keypair::new(), sgt: mint, sgt_account: token_account })
    }

    fn close_entry(&mut self, entry: &Pubkey, payer: &Pubkey) -> Result<(), String> {
        let signer = self.user();
        let ix = self.ix(instruction::CloseEntry {}, accounts::CloseEntry { payer: *payer, entry: *entry });
        self.send(ix, &[&signer])
    }

    fn close_receipt(&mut self, receipt: &Pubkey, payer: &Pubkey) -> Result<(), String> {
        let signer = self.user();
        let ix = self.ix(instruction::CloseReceipt {}, accounts::CloseReceipt { payer: *payer, receipt: *receipt });
        self.send(ix, &[&signer])
    }

    fn close_contest(&mut self, contest: &Pubkey, sponsor: &Pubkey) -> Result<(), String> {
        let payer = self.user();
        let ix = self.ix(
            instruction::CloseContest {},
            accounts::CloseContest {
                payer: wallet(&payer),
                sponsor: *sponsor,
                contest: *contest,
                vault: self.vault(contest),
                sponsor_token: get_associated_token_address(sponsor, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        );
        self.send(ix, &[&payer])
    }
}

fn title(text: &str) -> [u8; 32] {
    let mut out = [0; 32];
    out[..text.len()].copy_from_slice(text.as_bytes());
    out
}

fn skr_mint() -> Vec<u8> {
    let mut data = vec![0; spl_token::state::Mint::LEN];
    spl_token::state::Mint {
        mint_authority: Some(Pubkey::new_unique()).into(),
        supply: 1_000_000_000 * SKR,
        decimals: DECIMALS,
        is_initialized: true,
        freeze_authority: None.into(),
    }
    .pack_into_slice(&mut data);
    data
}

/// Token-2022 layout: base mint padded to 165 bytes, the account type, then TLV entries.
fn sgt_mint(mint: &Pubkey, member_mint: &Pubkey, group: &Pubkey) -> Vec<u8> {
    let mut data = vec![0; spl_token::state::Account::LEN];
    spl_token::state::Mint { mint_authority: None.into(), supply: 1, decimals: 0, is_initialized: true, freeze_authority: None.into() }
        .pack_into_slice(&mut data[..spl_token::state::Mint::LEN]);
    data.push(1);
    tlv(&mut data, GROUP_MEMBER_POINTER, &[[0; 32].as_slice(), mint.as_ref()].concat());
    tlv(&mut data, TOKEN_GROUP_MEMBER, &[member_mint.as_ref(), group.as_ref(), &42u64.to_le_bytes()].concat());
    data
}

fn test_authority() -> Pubkey {
    Pubkey::find_program_address(&[TEST_AUTHORITY_SEED], &program_id()).0
}

/// A Token-2022 group mint whose update authority is `authority`, as `devnet.py` creates it.
fn group_mint(group: &Pubkey, authority: &Pubkey) -> Vec<u8> {
    let mut data = vec![0; spl_token::state::Account::LEN];
    spl_token::state::Mint { mint_authority: Some(Pubkey::new_unique()).into(), supply: 0, decimals: 0, is_initialized: true, freeze_authority: None.into() }
        .pack_into_slice(&mut data[..spl_token::state::Mint::LEN]);
    data.push(1);
    tlv(&mut data, GROUP_POINTER, &[authority.as_ref(), group.as_ref()].concat());
    tlv(&mut data, TOKEN_GROUP, &[authority.as_ref(), group.as_ref(), &0u64.to_le_bytes(), &1_000_000u64.to_le_bytes()].concat());
    data
}

fn tlv(data: &mut Vec<u8>, kind: u16, value: &[u8]) {
    data.extend(kind.to_le_bytes());
    data.extend((value.len() as u16).to_le_bytes());
    data.extend(value);
}

fn token_account(mint: &Pubkey, owner: &Pubkey, amount: u64) -> Vec<u8> {
    let mut data = vec![0; spl_token::state::Account::LEN];
    spl_token::state::Account { mint: *mint, owner: *owner, amount, state: spl_token::state::AccountState::Initialized, ..Default::default() }
        .pack_into_slice(&mut data);
    data
}

fn bpf_loader_upgradeable() -> Pubkey {
    Pubkey::from_str_const("BPFLoaderUpgradeab1e11111111111111111111111")
}

fn system_program() -> Pubkey {
    Pubkey::default()
}

// ProgramData header written by LiteSVM without an authority: tag u32 | slot u64 | Option<Pubkey> at byte 12.
fn set_upgrade_authority(svm: &mut LiteSVM, authority: &Pubkey) {
    let program_data = addr(&Pubkey::find_program_address(&[program_id().as_ref()], &bpf_loader_upgradeable()).0);
    let mut account = svm.get_account(&program_data).unwrap();
    account.data[12] = 1;
    account.data[13..45].copy_from_slice(authority.as_ref());
    svm.set_account(program_data, account).unwrap();
}

fn set_time(svm: &mut LiteSVM, unix: i64) {
    let mut clock = svm.get_sysvar::<Clock>();
    clock.unix_timestamp = unix;
    svm.set_sysvar::<Clock>(&clock);
}

fn put(svm: &mut LiteSVM, key: &Pubkey, data: Vec<u8>, owner: &Pubkey) {
    let lamports = svm.minimum_balance_for_rent_exemption(data.len());
    svm.set_account(addr(key), Account { lamports, data, owner: addr(owner), executable: false, rent_epoch: 0 }).unwrap();
}

fn read<T: AccountDeserialize>(svm: &LiteSVM, key: &Pubkey) -> T {
    T::try_deserialize(&mut &svm.get_account(&addr(key)).unwrap().data[..]).unwrap()
}

fn expect_err<T: std::fmt::Debug>(result: Result<T, String>, code: u32) {
    let err = result.expect_err("expected the transaction to fail");
    assert!(err.contains(&format!("Custom({code})")), "unexpected error {err}, wanted Custom({code})");
}

fn code(error: VaultError) -> u32 {
    error as u32 + anchor_lang::error::ERROR_CODE_OFFSET
}

const ALREADY_IN_USE: u32 = 0;
const HAS_ONE: u32 = anchor_lang::error::ErrorCode::ConstraintHasOne as u32;

#[test]
fn roots_match_the_app() {
    // The app's RosterTreeTest pins the same values.
    let expected = [
        (1, "ecc024dbcf91045830cedc2e0ecd0315bc5bfd9d2ead1dbec20151e7c5d7ad9d"),
        (2, "fd9305db0088dde5775371b0d0af547afbaad864ad33c911dc59791a73ffbe5f"),
        (3, "0027d0372e39ec2ccbd8e699eb5e43558c1a5676d215289048576ad244a77bf3"),
        (5, "d4dda27d606f8199b30ddbb6168691a8ee42d5d63df0869a65c9c465417d4c27"),
    ];
    let hex = |r: [u8; 32]| r.iter().map(|b| format!("{b:02x}")).collect::<String>();
    for (n, expected) in expected {
        assert_eq!(hex(Roster::new(&reference_keys(n)).root()), expected, "{n} guests");
    }
    // Even guests bound to a wallet of 32 bytes of 0xA0 + i, odd ones unbound.
    let mixed = |n: usize| {
        reference_keys(n).into_iter().enumerate().map(|(i, k)| (k, if i % 2 == 0 { [0xA0 + i as u8; 32] } else { UNBOUND })).collect::<Vec<_>>()
    };
    assert_eq!(hex(Roster::with_wallets(&mixed(3)).root()), "39125bb563ad6de5958fed28e25d91e70f6edd3572f51bed1066687bfb5dae24");
    assert_eq!(hex(Roster::with_wallets(&mixed(5)).root()), "c9c1d9296665cd67830af99a9a1e9dc256b633fe36961b1eaa03868caec53664");
    for n in 1..=64 {
        let keys = reference_keys(n);
        let roster = Roster::new(&keys);
        for (i, key) in keys.iter().enumerate() {
            let proof = roster.proof(i);
            assert!(proof.len() <= formation_vault::MAX_PROOF);
            assert_eq!(formation_vault::roster_root(i as u8, key, &UNBOUND, &proof), roster.root(), "guest {i} of {n}");
        }
    }
}

#[test]
fn the_owner_gets_weight_times_each_guest() {
    assert_eq!(split(300 * SKR, 3, 1).unwrap(), (225 * SKR, 75 * SKR));
    assert_eq!(split(300 * SKR, 3, 3).unwrap(), (150 * SKR, 50 * SKR));
    assert_eq!(split(300 * SKR, 3, 9).unwrap(), (75 * SKR, 25 * SKR));
    assert_eq!(split(100, 3, 4).unwrap(), (44, 14));
    let (owner, share) = split(u64::MAX, 100, 64).unwrap();
    assert_eq!(owner + share * 64, u64::MAX);
    assert!(split(100, 3, 0).is_err());
}

#[test]
fn the_draw_selects_exactly_k_of_n() {
    for n in [1u32, 2, 3, 7, 16, 17, 100, 1_000] {
        for seed in [[0u8; 32], [1; 32], [0xAB; 32]] {
            let mut seen = vec![false; n as usize];
            for i in 0..n {
                let p = permute(&seed, n, i);
                assert!(p < n && !seen[p as usize], "n={n}");
                seen[p as usize] = true;
            }
        }
    }
    // The app's SelectionTest pins the same values.
    assert_eq!((0..10).map(|i| permute(&[5; 32], 10, i)).collect::<Vec<_>>(), [5, 7, 9, 0, 6, 2, 1, 3, 8, 4]);
    let seed: [u8; 32] = std::array::from_fn(|i| i as u8);
    assert_eq!([0, 1, 2, 50, 99].map(|i| permute(&seed, 100, i)), [80, 34, 24, 3, 2]);
    let picks = |seed: [u8; 32]| (0..100u32).filter(|&i| permute(&seed, 100, i) < 10).collect::<Vec<_>>();
    assert_ne!(picks([1; 32]), picks([2; 32]), "the seed decides who wins");
}

#[test]
fn config_needs_the_upgrade_authority() {
    let mut env = Env::without_config();
    let intruder = env.user();
    expect_err(env.init_config(&intruder, defaults()), code(VaultError::Unauthorized));
    let admin = env.admin.insecure_clone();
    expect_err(env.init_config(&admin, Settings { owner_weight: 0, ..defaults() }), code(VaultError::BadSettings));

    env.init_config(&admin, defaults()).unwrap();
    let config: Config = read(&env.svm, &config_pda());
    assert_eq!((config.admin, config.mint, config.sgt_group, config.vrf_program), (wallet(&admin), env.mint, env.group, VRF));
    assert_eq!(config.settings, defaults());
    expect_err(env.init_config(&admin, defaults()), ALREADY_IN_USE);
}

#[test]
fn only_the_admin_changes_settings_and_only_within_bounds() {
    let mut env = Env::new();
    let admin = env.admin.insecure_clone();
    let intruder = env.user();
    let group = Pubkey::new_unique();
    expect_err(env.admin(&intruder, instruction::SetSgtGroup { sgt_group: group }), code(VaultError::Unauthorized));
    expect_err(env.admin(&intruder, instruction::SetSettings { settings: defaults() }), code(VaultError::Unauthorized));
    expect_err(env.admin(&intruder, instruction::SetVrfProgram { vrf_program: group }), code(VaultError::Unauthorized));
    expect_err(env.admin(&intruder, instruction::SetAdmin { admin: wallet(&intruder) }), code(VaultError::Unauthorized));

    let out_of_bounds = [
        Settings { modes: 0, ..defaults() },
        Settings { modes: 4, ..defaults() },
        Settings { owner_weight: 101, ..defaults() },
        Settings { min_guest_share: 0, ..defaults() },
        Settings { min_budgets: 0, ..defaults() },
        Settings { max_per_key: 0, ..defaults() },
        Settings { max_per_key: 65, ..defaults() },
        Settings { max_wins_per_sgt: 0, ..defaults() },
        Settings { guest_ceiling: 65, ..defaults() },
        Settings { claim_window: 60, ..defaults() },
        Settings { min_play_window: 0, ..defaults() },
        Settings { min_play_window: 2 * HOUR, max_play_window: HOUR, ..defaults() },
        Settings { max_enter_window: 400 * DAY, ..defaults() },
        Settings { draw_timeout: 10, ..defaults() },
        Settings { min_guest_share: u64::MAX, ..defaults() },
    ];
    for settings in out_of_bounds {
        expect_err(env.admin(&admin, instruction::SetSettings { settings }), code(VaultError::BadSettings));
    }

    env.set_settings(|s| s.owner_weight = 5);
    assert_eq!(read::<Config>(&env.svm, &config_pda()).settings.owner_weight, 5);
    env.admin(&admin, instruction::SetSgtGroup { sgt_group: group }).unwrap();
    assert_eq!(read::<Config>(&env.svm, &config_pda()).sgt_group, group);

    let next = env.user();
    env.admin(&admin, instruction::SetAdmin { admin: wallet(&next) }).unwrap();
    expect_err(env.admin(&admin, instruction::SetSgtGroup { sgt_group: env.group }), code(VaultError::Unauthorized));
    env.admin(&next, instruction::SetSgtGroup { sgt_group: env.group }).unwrap();
}

#[test]
fn first_come_pays_each_genesis_token_once() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let c = env.contest(&contest);
    assert_eq!((c.pool, c.unallocated, c.budget, c.max_guests, c.wins_per_sgt), (3_000 * SKR, 3_000 * SKR, 300 * SKR, 27, 1));
    assert_eq!((c.sponsor, c.enter_until, c.play_until, c.settings), (wallet(&env.sponsor), NOW, NOW + DAY, defaults()));
    assert_eq!(&c.title[..13], b"Genesis Rally");
    assert_eq!(env.balance(&wallet(&env.sponsor)), 97_000 * SKR);
    expect_err(env.create(&terms), ALREADY_IN_USE);

    let crew = guests(3);
    let roster = Roster::of(&crew);
    let seeker = env.new_seeker();
    env.unlock(&seeker, &contest, 0, &roster, 3).unwrap();
    assert_eq!(env.balance(&seeker.key()), 150 * SKR);
    let entry = env.entry(&contest, &seeker.sgt, 0);
    assert_eq!(entry.state, EntryState::Unlocked);
    assert_eq!((entry.owner, entry.budget, entry.guest_share, entry.owner_paid), (seeker.key(), 300 * SKR, 50 * SKR, 150 * SKR));
    assert_eq!((entry.roster_root, entry.roster_size, entry.result, entry.closes_at), (roster.root(), 3, RESULT, NOW + DAY + CLAIM_WINDOW));
    assert_eq!((env.contest(&contest).unallocated, env.contest(&contest).unlocks), (2_700 * SKR, 1));

    expect_err(env.unlock(&seeker, &contest, 0, &roster, 3), ALREADY_IN_USE);
    expect_err(env.unlock(&seeker, &contest, 1, &roster, 3), code(VaultError::BadWins));
    let moved = env.move_sgt(&seeker);
    expect_err(env.unlock(&moved, &contest, 0, &roster, 3), ALREADY_IN_USE);

    let entry = entry_pda(&contest, &seeker.sgt, 0);
    for (i, guest) in crew.iter().enumerate() {
        let payer = env.user();
        env.claim(&payer, guest, &wallet(guest), &contest, &entry, i as u8, roster.proof(i)).unwrap();
        assert_eq!(env.balance(&wallet(guest)), 50 * SKR);
    }
    assert_eq!(env.token_balance(&env.vault(&contest)), 2_700 * SKR);
}

#[test]
fn a_moved_token_pays_its_new_holder() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let seeker = env.new_seeker();
    let moved = env.move_sgt(&seeker);
    let roster = Roster::of(&guests(1));

    expect_err(env.unlock(&seeker, &contest, 0, &roster, 1), code(VaultError::NotASeeker));
    env.unlock(&moved, &contest, 0, &roster, 1).unwrap();
    assert_eq!(env.balance(&moved.key()), 225 * SKR);
    assert_eq!(env.entry(&contest, &seeker.sgt, 0).owner, moved.key());
}

#[test]
fn the_pool_runs_out() {
    let mut env = Env::new();
    let terms = env.first_come(400 * SKR + 39 * SKR, 40 * SKR);
    let contest = env.create(&terms).unwrap();
    let roster = Roster::of(&guests(1));
    for _ in 0..10 {
        let seeker = env.new_seeker();
        env.unlock(&seeker, &contest, 0, &roster, 1).unwrap();
        assert_eq!(env.balance(&seeker.key()), 30 * SKR);
    }
    let late = env.new_seeker();
    expect_err(env.unlock(&late, &contest, 0, &roster, 1), code(VaultError::PoolSpent));
    assert_eq!(env.contest(&contest).unallocated, 39 * SKR);
}

#[test]
fn a_sponsor_may_let_one_token_win_more_than_once() {
    let mut env = Env::new();
    let too_many = Terms { wins: 4, ..env.first_come(3_000 * SKR, 300 * SKR) };
    expect_err(env.create(&too_many), code(VaultError::BadWins));

    let terms = Terms { wins: 2, ..env.first_come(3_000 * SKR, 300 * SKR) };
    let contest = env.create(&terms).unwrap();
    let seeker = env.new_seeker();
    let roster = Roster::of(&guests(1));
    env.unlock(&seeker, &contest, 0, &roster, 1).unwrap();
    env.unlock(&seeker, &contest, 1, &roster, 1).unwrap();
    expect_err(env.unlock(&seeker, &contest, 2, &roster, 1), code(VaultError::BadWins));
    assert_eq!(env.balance(&seeker.key()), 450 * SKR);
    assert_eq!(env.contest(&contest).unlocks, 2);
}

#[test]
fn a_contest_for_one_seeker() {
    let mut env = Env::new();
    let seeker = env.new_seeker();
    let terms = Terms { only: seeker.sgt, ..env.first_come(300 * SKR, 300 * SKR) };
    let contest = env.create(&terms).unwrap();
    let roster = Roster::of(&guests(3));

    let other = env.new_seeker();
    expect_err(env.unlock(&other, &contest, 0, &roster, 3), code(VaultError::NotThisSeeker));
    env.unlock(&seeker, &contest, 0, &roster, 3).unwrap();
    assert_eq!(env.balance(&seeker.key()), 150 * SKR);
    expect_err(env.unlock(&seeker, &contest, 0, &roster, 3), ALREADY_IN_USE);
}

#[test]
fn rosters_stay_within_the_guest_limit() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let roster = Roster::new(&reference_keys(28));
    let seeker = env.new_seeker();
    expect_err(env.unlock(&seeker, &contest, 0, &roster, 0), code(VaultError::RosterSize));
    expect_err(env.unlock(&seeker, &contest, 0, &roster, 28), code(VaultError::RosterSize));
    env.unlock(&seeker, &contest, 0, &roster, 27).unwrap();
    assert!(env.entry(&contest, &seeker.sgt, 0).guest_share >= 10 * SKR);

    let large = env.first_come(100_000 * SKR - 3_000 * SKR, 9_700 * SKR);
    let contest = env.create(&large).unwrap();
    assert_eq!(env.contest(&contest).max_guests, 64);
}

#[test]
fn contests_must_make_sense() {
    let mut env = Env::new();
    let base = env.first_come(3_000 * SKR, 300 * SKR);
    let cases = [
        (Terms { amount: 0, ..base.clone() }, VaultError::ZeroAmount),
        (Terms { budget: 39 * SKR, ..base.clone() }, VaultError::BadBudget),
        (Terms { budget: 3_001 * SKR, ..base.clone() }, VaultError::BadBudget),
        (Terms { budget: 301 * SKR, ..base.clone() }, VaultError::PoolTooSmall),
        (Terms { wins: 0, ..base.clone() }, VaultError::BadWins),
        (Terms { play_until: NOW + HOUR - 1, ..base.clone() }, VaultError::BadWindow),
        (Terms { play_until: NOW + 91 * DAY, ..base.clone() }, VaultError::BadWindow),
        (Terms { play_until: NOW - 1, ..base.clone() }, VaultError::BadWindow),
    ];
    for (terms, error) in cases {
        expect_err(env.create(&terms), code(error));
    }

    let lottery = env.draw(400 * SKR, 5_000);
    let cases = [
        (Terms { mode: Mode::Draw { bps: 0 }, ..lottery.clone() }, VaultError::BadShare),
        (Terms { mode: Mode::Draw { bps: 10_001 }, ..lottery.clone() }, VaultError::BadShare),
        (Terms { only: Pubkey::new_unique(), ..lottery.clone() }, VaultError::BadMode),
        (Terms { wins: 2, ..lottery.clone() }, VaultError::BadWins),
        (Terms { amount: 399 * SKR, ..lottery.clone() }, VaultError::PoolTooSmall),
        (Terms { enter_until: NOW + 31 * DAY, play_until: NOW + 32 * DAY, ..lottery.clone() }, VaultError::BadWindow),
        (Terms { play_until: NOW + DAY, ..lottery.clone() }, VaultError::BadWindow),
    ];
    for (terms, error) in cases {
        expect_err(env.create(&terms), code(error));
    }

    let other = Pubkey::new_unique();
    put(&mut env.svm, &other, skr_mint(), &spl_token::ID);
    let sponsor = wallet(&env.sponsor);
    put(&mut env.svm, &get_associated_token_address(&sponsor, &other), token_account(&other, &sponsor, 10_000 * SKR), &spl_token::ID);
    expect_err(env.create(&Terms { mint: other, ..base.clone() }), code(VaultError::WrongMint));

    env.set_settings(|s| s.modes = MODE_DRAW);
    expect_err(env.create(&base), code(VaultError::ModeNotAllowed));
    env.set_settings(|s| s.modes = MODE_FIRST_COME);
    expect_err(env.create(&lottery), code(VaultError::ModeNotAllowed));
    env.set_settings(|s| s.paused = true);
    expect_err(env.create(&base), code(VaultError::Paused));
    env.set_settings(|s| s.paused = false);
    env.create(&base).unwrap();
}

#[test]
fn a_contest_keeps_the_settings_it_was_funded_under() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    env.set_settings(|s| {
        s.owner_weight = 1;
        s.max_per_key = 1;
    });
    let seeker = env.new_seeker();
    env.unlock(&seeker, &contest, 0, &Roster::of(&guests(1)), 1).unwrap();
    assert_eq!(env.balance(&seeker.key()), 225 * SKR);
}

#[test]
fn only_a_seeker_genesis_token_unlocks() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let roster = Roster::of(&guests(1));
    let holder = env.user();
    let owner = wallet(&holder);
    let unlock = |env: &mut Env, sgt: Pubkey, sgt_account: Pubkey| {
        let seeker = Seeker { wallet: holder.insecure_clone(), sgt, sgt_account };
        env.unlock(&seeker, &contest, 0, &roster, 1)
    };

    let other_group = Pubkey::new_unique();
    let fake = Pubkey::new_unique();
    put(&mut env.svm, &fake, sgt_mint(&fake, &fake, &other_group), &TOKEN_2022);
    let fake_account = env.token_account(&fake, &owner, 1, &TOKEN_2022);
    expect_err(unlock(&mut env, fake, fake_account), code(VaultError::NotASeeker));

    let real = env.new_seeker();
    let copy = Pubkey::new_unique();
    put(&mut env.svm, &copy, sgt_mint(&copy, &real.sgt, &env.group), &TOKEN_2022);
    let copy_account = env.token_account(&copy, &owner, 1, &TOKEN_2022);
    expect_err(unlock(&mut env, copy, copy_account), code(VaultError::NotASeeker));

    let classic = Pubkey::new_unique();
    put(&mut env.svm, &classic, sgt_mint(&classic, &classic, &env.group), &spl_token::ID);
    let classic_account = env.token_account(&classic, &owner, 1, &TOKEN_2022);
    expect_err(unlock(&mut env, classic, classic_account), code(VaultError::NotASeeker));

    let plain = Pubkey::new_unique();
    let mut data = vec![0; spl_token::state::Mint::LEN];
    spl_token::state::Mint { supply: 1, is_initialized: true, ..Default::default() }.pack_into_slice(&mut data);
    put(&mut env.svm, &plain, data, &TOKEN_2022);
    let plain_account = env.token_account(&plain, &owner, 1, &TOKEN_2022);
    expect_err(unlock(&mut env, plain, plain_account), code(VaultError::NotASeeker));

    expect_err(unlock(&mut env, real.sgt, real.sgt_account), code(VaultError::NotASeeker));
    let forged = env.token_account(&real.sgt, &owner, 1, &spl_token::ID);
    expect_err(unlock(&mut env, real.sgt, forged), code(VaultError::NotASeeker));

    // The contest keeps the group it was funded under.
    let admin = env.admin.insecure_clone();
    env.admin(&admin, instruction::SetSgtGroup { sgt_group: other_group }).unwrap();
    expect_err(unlock(&mut env, fake, fake_account), code(VaultError::NotASeeker));
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let seeker = Seeker { wallet: holder.insecure_clone(), sgt: fake, sgt_account: fake_account };
    env.unlock(&seeker, &contest, 0, &roster, 1).unwrap();
}

#[test]
fn a_real_seeker_genesis_token_unlocks() {
    // Copied from mainnet: the mint has other extensions before the member entry, and the holder's
    // account is frozen with an immutable owner. The account's owner is swapped for a key the test holds.
    let mut env = Env::new();
    let group = Pubkey::from_str_const("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
    let sgt = Pubkey::from_str_const("JBbD5StDRA4rah3yMYUPEXhs8A9mW7mbnqcvzeMo4dZR");
    let sgt_account = Pubkey::from_str_const("EduidFzPEXBhMsJQM8GL3kFXRxXurngCRG2VSV1apdvT");
    let holder = env.user();
    let mut account = include_bytes!("fixtures/sgt-account.bin").to_vec();
    account[32..64].copy_from_slice(wallet(&holder).as_ref());
    put(&mut env.svm, &sgt, include_bytes!("fixtures/sgt-mint.bin").to_vec(), &TOKEN_2022);
    put(&mut env.svm, &sgt_account, account, &TOKEN_2022);
    let seeker = Seeker { wallet: holder, sgt, sgt_account };
    let roster = Roster::of(&guests(1));

    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    expect_err(env.unlock(&seeker, &contest, 0, &roster, 1), code(VaultError::NotASeeker));
    let admin = env.admin.insecure_clone();
    env.admin(&admin, instruction::SetSgtGroup { sgt_group: group }).unwrap();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    env.unlock(&seeker, &contest, 0, &roster, 1).unwrap();
    assert_eq!(env.balance(&seeker.key()), 225 * SKR);
}

#[test]
fn a_claim_needs_the_claim_key_and_its_proof() {
    let mut env = Env::new();
    let terms = env.first_come(6_000 * SKR, 600 * SKR);
    let contest = env.create(&terms).unwrap();
    let crew = guests(4);
    let roster = Roster::of(&crew);
    let seeker = env.new_seeker();
    let entry = entry_pda(&contest, &seeker.sgt, 0);
    let payer = env.user();
    let recipient = Pubkey::new_unique();

    let other = env.new_seeker();
    env.unlock(&other, &contest, 0, &Roster::of(&guests(1)), 1).unwrap();
    expect_err(env.claim(&payer, &crew[0], &recipient, &contest, &entry_pda(&contest, &other.sgt, 0), 0, roster.proof(0)), code(VaultError::NotInRoster));
    env.unlock(&seeker, &contest, 0, &roster, 4).unwrap();
    assert_eq!(env.balance(&seeker.key()), split(600 * SKR, 3, 4).unwrap().0);

    let stranger = Keypair::new();
    expect_err(env.claim(&payer, &stranger, &recipient, &contest, &entry, 0, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &crew[0], &recipient, &contest, &entry, 1, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &crew[0], &recipient, &contest, &entry, 0, roster.proof(1)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &crew[0], &recipient, &contest, &entry, 4, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &crew[0], &recipient, &contest, &entry, 0, vec![[0; 32]; 8]), code(VaultError::ProofTooLong));

    let share = 600 * SKR / 7;
    env.claim(&payer, &crew[0], &recipient, &contest, &entry, 0, roster.proof(0)).unwrap();
    assert_eq!(env.balance(&recipient), share);
    expect_err(env.claim(&payer, &crew[0], &Pubkey::new_unique(), &contest, &entry, 0, roster.proof(0)), code(VaultError::AlreadyClaimed));
    env.claim(&payer, &crew[1], &wallet(&crew[1]), &contest, &entry, 1, roster.proof(1)).unwrap();
    assert_eq!(env.entry(&contest, &seeker.sgt, 0).claimed, 0b11);
    let receipt: Receipt = read(&env.svm, &receipt_pda(&contest, &wallet(&crew[0])));
    assert_eq!((receipt.count, receipt.payer, receipt.claim_key), (1, wallet(&payer), wallet(&crew[0])));
}

#[test]
fn a_claim_key_is_paid_a_limited_number_of_times_per_contest() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let farmer = Keypair::new();
    let roster = Roster::of(&[farmer.insecure_clone()]);
    let payer = env.user();
    for round in 0..4 {
        let seeker = env.new_seeker();
        env.unlock(&seeker, &contest, 0, &roster, 1).unwrap();
        let result = env.claim(&payer, &farmer, &wallet(&farmer), &contest, &entry_pda(&contest, &seeker.sgt, 0), 0, roster.proof(0));
        if round < 3 {
            result.unwrap();
        } else {
            expect_err(result, code(VaultError::ClaimLimit));
        }
    }
    assert_eq!(env.balance(&wallet(&farmer)), 3 * 75 * SKR);

    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let other = env.create(&terms).unwrap();
    let seeker = env.new_seeker();
    env.unlock(&seeker, &other, 0, &roster, 1).unwrap();
    env.claim(&payer, &farmer, &wallet(&farmer), &other, &entry_pda(&other, &seeker.sgt, 0), 0, roster.proof(0)).unwrap();
}

#[test]
fn bound_shares_land_in_the_guests_wallet_without_their_signature() {
    let mut env = Env::new();
    let terms = env.first_come(6_000 * SKR, 600 * SKR);
    let contest = env.create(&terms).unwrap();
    let crew = guests(4);
    let wallets: Vec<Pubkey> = (0..4).map(|_| Pubkey::new_unique()).collect();
    let entries: Vec<(Pubkey, [u8; 32])> =
        crew.iter().enumerate().map(|(i, h)| (wallet(h), if i % 2 == 0 { wallets[i].to_bytes() } else { UNBOUND })).collect();
    let roster = Roster::with_wallets(&entries);
    let seeker = env.new_seeker();
    env.unlock(&seeker, &contest, 0, &roster, 4).unwrap();
    let entry = entry_pda(&contest, &seeker.sgt, 0);
    let payer = env.user();

    let stranger = Pubkey::new_unique();
    let share = 600 * SKR / 7;
    expect_err(env.crank(&payer, &wallet(&crew[0]), &stranger, &contest, &entry, 0, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &crew[0], &stranger, &contest, &entry, 0, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.crank(&payer, &wallet(&crew[1]), &stranger, &contest, &entry, 1, roster.proof(1)), code(VaultError::NotInRoster));

    env.crank(&payer, &wallet(&crew[0]), &wallets[0], &contest, &entry, 0, roster.proof(0)).unwrap();
    env.crank(&payer, &wallet(&crew[2]), &wallets[2], &contest, &entry, 2, roster.proof(2)).unwrap();
    assert_eq!((env.balance(&wallets[0]), env.balance(&wallets[2])), (share, share));
    expect_err(env.crank(&payer, &wallet(&crew[0]), &wallets[0], &contest, &entry, 0, roster.proof(0)), code(VaultError::AlreadyClaimed));

    env.claim(&payer, &crew[1], &stranger, &contest, &entry, 1, roster.proof(1)).unwrap();
    assert_eq!(env.balance(&stranger), share);
    assert_eq!(env.entry(&contest, &seeker.sgt, 0).claimed, 0b0111);
}

#[test]
fn everything_left_goes_back_after_the_claim_window() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    let crew = guests(3);
    let roster = Roster::of(&crew);
    let seeker = env.new_seeker();
    env.unlock(&seeker, &contest, 0, &roster, 3).unwrap();
    let entry = entry_pda(&contest, &seeker.sgt, 0);
    let payer = env.user();
    env.claim(&payer, &crew[0], &wallet(&crew[0]), &contest, &entry, 0, roster.proof(0)).unwrap();
    let receipt = receipt_pda(&contest, &wallet(&crew[0]));
    let sponsor = wallet(&env.sponsor);

    expect_err(env.close_contest(&contest, &sponsor), code(VaultError::NotClosable));
    expect_err(env.close_entry(&entry, &seeker.key()), code(VaultError::NotClosable));
    expect_err(env.close_receipt(&receipt, &wallet(&payer)), code(VaultError::NotClosable));

    set_time(&mut env.svm, terms.play_until);
    let late = env.new_seeker();
    expect_err(env.unlock(&late, &contest, 0, &roster, 3), code(VaultError::Expired));
    env.claim(&payer, &crew[1], &wallet(&crew[1]), &contest, &entry, 1, roster.proof(1)).unwrap();

    let closes_at = terms.play_until + CLAIM_WINDOW;
    set_time(&mut env.svm, closes_at - 1);
    expect_err(env.close_contest(&contest, &sponsor), code(VaultError::NotClosable));
    set_time(&mut env.svm, closes_at);
    expect_err(env.claim(&payer, &crew[2], &wallet(&crew[2]), &contest, &entry, 2, roster.proof(2)), code(VaultError::ClaimWindowClosed));

    let stranger = Pubkey::new_unique();
    expect_err(env.close_contest(&contest, &stranger), HAS_ONE);
    expect_err(env.close_entry(&entry, &stranger), HAS_ONE);
    env.close_contest(&contest, &sponsor).unwrap();
    assert_eq!(env.balance(&sponsor), 100_000 * SKR - 150 * SKR - 2 * 50 * SKR);
    assert!(!env.exists(&contest) && !env.exists(&env.vault(&contest)));

    let rent = env.lamports(&entry);
    let before = env.lamports(&seeker.key());
    env.close_entry(&entry, &seeker.key()).unwrap();
    assert_eq!(env.lamports(&seeker.key()), before + rent);
    env.close_receipt(&receipt, &wallet(&payer)).unwrap();
    assert!(!env.exists(&entry) && !env.exists(&receipt));
}

#[test]
fn the_unlock_and_every_payout_fit_one_transaction() {
    let mut env = Env::new();
    let terms = env.first_come(4_000 * SKR, 400 * SKR);
    let contest = env.create(&terms).unwrap();
    let crew: Vec<Pubkey> = (0..3).map(|_| Pubkey::new_unique()).collect();
    let wallets: Vec<Pubkey> = (0..3).map(|_| Pubkey::new_unique()).collect();
    let roster = Roster::with_wallets(&crew.iter().zip(&wallets).map(|(h, w)| (*h, w.to_bytes())).collect::<Vec<_>>());
    let seeker = env.new_seeker();
    let entry = entry_pda(&contest, &seeker.sgt, 0);
    let mut ixs = vec![env.unlock_ix(&seeker, &contest, 0, roster.root(), 3)];
    for i in 0..3 {
        ixs.push(env.claim_ix(&seeker.wallet, &crew[i], &wallets[i], &contest, &entry, i as u8, roster.proof(i)));
    }
    let size = env.send_all(&ixs, &[&seeker.wallet]).unwrap();
    assert!(size <= 1_232, "{size} bytes");
    let (owner, share) = split(400 * SKR, 3, 3).unwrap();
    assert_eq!(env.balance(&seeker.key()), owner);
    for w in &wallets {
        assert_eq!(env.balance(w), share);
    }
}

#[test]
fn a_draw_selects_its_share_of_the_entrants() {
    let mut env = Env::new();
    let terms = env.draw(1_000 * SKR, 3_000);
    let contest = env.create(&terms).unwrap();
    let seekers: Vec<Seeker> = (0..10).map(|_| env.new_seeker()).collect();
    for s in &seekers {
        env.register(s, &contest).unwrap();
    }
    expect_err(env.register(&seekers[0], &contest), ALREADY_IN_USE);
    assert_eq!(env.entry(&contest, &seekers[3].sgt, 0).index, 3);
    expect_err(env.request_draw(&contest), code(VaultError::NotDrawTime));

    set_time(&mut env.svm, terms.enter_until);
    let late = env.new_seeker();
    expect_err(env.register(&late, &contest), code(VaultError::EntryClosed));
    let roster = Roster::of(&guests(1));
    expect_err(env.unlock_drawn(&seekers[0], &contest, &roster, 1), code(VaultError::NotDrawTime));

    let seed = env.request_draw(&contest).unwrap();
    let request = env.svm.get_account(&addr(&request_pda(&seed))).expect("ORAO created the request");
    assert_eq!(pk(&request.owner), VRF);
    expect_err(env.finalize(&contest, &request_pda(&seed)), code(VaultError::DrawPending));
    expect_err(env.request_draw(&contest), code(VaultError::DrawPending));

    env.fulfill(&seed, [5; 64]);
    let forged = Pubkey::new_unique();
    expect_err(env.finalize(&contest, &forged), code(VaultError::BadRandomness));
    env.finalize(&contest, &request_pda(&seed)).unwrap();
    let c = env.contest(&contest);
    assert!(c.draw.done);
    assert_eq!((c.entered, c.selected, c.budget, c.max_guests), (10, 3, 1_000 * SKR / 3, 30));
    expect_err(env.request_draw(&contest), code(VaultError::AlreadyDrawn));

    let mut winners = 0;
    for s in &seekers {
        let index = env.entry(&contest, &s.sgt, 0).index;
        let result = env.unlock_drawn(s, &contest, &roster, 1);
        if permute(&c.draw.randomness, 10, index) < 3 {
            result.unwrap();
            winners += 1;
            assert_eq!(env.balance(&s.key()), split(c.budget, 3, 1).unwrap().0);
            expect_err(env.unlock_drawn(s, &contest, &roster, 1), code(VaultError::AlreadyUnlocked));
        } else {
            expect_err(result, code(VaultError::NotSelected));
        }
    }
    assert_eq!(winners, 3);
    assert_eq!(env.contest(&contest).unallocated, 1_000 * SKR - 3 * c.budget);
}

#[test]
fn a_small_pool_selects_fewer_and_few_entrants_all_win() {
    let mut env = Env::new();
    let terms = env.draw(400 * SKR, 10_000);
    let (contest, _) = env.drawn(&terms, 20);
    let c = env.contest(&contest);
    assert_eq!((c.selected, c.budget, c.max_guests), (10, 40 * SKR, 1));

    let mut env = Env::new();
    let terms = env.draw(1_000 * SKR, 5_000);
    let (contest, seekers) = env.drawn(&terms, 1);
    assert_eq!(env.contest(&contest).selected, 1);
    env.unlock_drawn(&seekers[0], &contest, &Roster::of(&guests(1)), 1).unwrap();
    assert_eq!(env.balance(&seekers[0].key()), 750 * SKR);
}

#[test]
fn an_unanswered_draw_can_be_requested_again() {
    let mut env = Env::new();
    let terms = env.draw(1_000 * SKR, 5_000);
    let contest = env.create(&terms).unwrap();
    let seeker = env.new_seeker();
    env.register(&seeker, &contest).unwrap();
    set_time(&mut env.svm, terms.enter_until);
    let first = env.request_draw(&contest).unwrap();

    set_time(&mut env.svm, terms.enter_until + HOUR - 1);
    expect_err(env.request_draw(&contest), code(VaultError::DrawPending));
    set_time(&mut env.svm, terms.enter_until + HOUR);
    let second = env.request_draw(&contest).unwrap();
    assert_ne!(first, second);

    // A late answer to the first request no longer counts.
    env.fulfill(&first, [1; 64]);
    expect_err(env.finalize(&contest, &request_pda(&first)), code(VaultError::BadRandomness));

    // Nobody can throw away an answer they've seen by asking again.
    env.fulfill(&second, [2; 64]);
    set_time(&mut env.svm, terms.enter_until + 3 * HOUR);
    expect_err(env.request_draw(&contest), code(VaultError::DrawPending));
    env.finalize(&contest, &request_pda(&second)).unwrap();
}

#[test]
fn each_mode_keeps_to_its_own_instructions() {
    let mut env = Env::new();
    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let first_come = env.create(&terms).unwrap();
    let seeker = env.new_seeker();
    expect_err(env.register(&seeker, &first_come), code(VaultError::BadMode));
    expect_err(env.request_draw(&first_come), code(VaultError::BadMode));

    let terms = env.draw(1_000 * SKR, 5_000);
    let draw = env.create(&terms).unwrap();
    expect_err(env.unlock(&seeker, &draw, 0, &Roster::of(&guests(1)), 1), code(VaultError::BadMode));
    set_time(&mut env.svm, terms.enter_until);
    expect_err(env.request_draw(&draw), code(VaultError::NoEntries));
}

#[test]
fn any_wallet_can_mint_one_test_token_that_unlocks_like_a_real_one() {
    let mut env = Env::new();
    let group = env.group;
    put(&mut env.svm, &group, group_mint(&group, &test_authority()), &TOKEN_2022);
    let holder = env.user();
    expect_err(env.mint_test_token(&wallet(&holder)).map(|_| ()), code(VaultError::TestTokensOff));

    env.set_settings(|s| s.test_tokens = true);
    let minted = env.mint_test_token(&wallet(&holder)).unwrap();
    expect_err(env.mint_test_token(&wallet(&holder)).map(|_| ()), ALREADY_IN_USE);
    let seeker = Seeker { wallet: holder, ..minted };
    let account = env.svm.get_account(&addr(&seeker.sgt_account)).unwrap();
    assert_eq!(spl_token::state::Account::unpack(&account.data[..spl_token::state::Account::LEN]).unwrap().amount, 1);

    let terms = env.first_come(3_000 * SKR, 300 * SKR);
    let contest = env.create(&terms).unwrap();
    env.unlock(&seeker, &contest, 0, &Roster::of(&guests(1)), 1).unwrap();
    assert_eq!(env.balance(&seeker.key()), 225 * SKR);
}

#[test]
fn only_the_vaults_own_group_takes_test_tokens() {
    let mut env = Env::new();
    let group = env.group;
    put(&mut env.svm, &group, group_mint(&group, &Pubkey::new_unique()), &TOKEN_2022);
    env.set_settings(|s| s.test_tokens = true);
    assert!(env.mint_test_token(&Pubkey::new_unique()).is_err());
}
