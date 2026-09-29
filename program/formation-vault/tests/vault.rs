use anchor_lang::prelude::Pubkey;
use anchor_lang::solana_program::program_pack::Pack;
use anchor_lang::{AccountDeserialize, InstructionData, ToAccountMetas};
use anchor_spl::associated_token::{self, get_associated_token_address};
use anchor_spl::token::spl_token;
use formation_vault::{accounts, instruction, split, Config, Opportunity, OpportunityState, VaultError, CLAIM_WINDOW, CONFIG_SEED, OPPORTUNITY_SEED, TOKEN_2022, UNBOUND};
use litesvm::LiteSVM;
use solana_account::Account;
use solana_address::Address;
use solana_clock::Clock;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::{Message, VersionedMessage};
use solana_sha256_hasher::hashv;
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
const DAY: i64 = 24 * 3_600;
const RALLY: u16 = 1;
const RESULT: [u8; 32] = [7; 32];

const GROUP_MEMBER_POINTER: u16 = 22;
const TOKEN_GROUP_MEMBER: u16 = 23;

/// Mirrors the app's RosterTree.
struct Roster {
    levels: Vec<Vec<[u8; 32]>>,
}

impl Roster {
    fn new(keys: &[Pubkey]) -> Self {
        Self::with_wallets(&keys.iter().map(|k| (*k, UNBOUND)).collect::<Vec<_>>())
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

struct Seeker {
    wallet: Keypair,
    sgt: Pubkey,
    sgt_account: Pubkey,
}

#[derive(Clone)]
struct Lock {
    id: [u8; 16],
    amount: u64,
    players: u8,
    owner_bps: u16,
    difficulty: u8,
    title: [u8; 32],
    expires_at: i64,
    seeker: Pubkey,
    sgt: Pubkey,
    sgt_account: Pubkey,
    mint: Pubkey,
}

struct Env {
    svm: LiteSVM,
    admin: Keypair,
    sponsor: Keypair,
    seeker: Seeker,
    mint: Pubkey,
    group: Pubkey,
    next_id: u8,
}

fn program_id() -> Pubkey {
    formation_vault::ID
}

fn config_pda() -> Pubkey {
    Pubkey::find_program_address(&[CONFIG_SEED], &program_id()).0
}

fn opportunity_pda(id: &[u8; 16]) -> Pubkey {
    Pubkey::find_program_address(&[OPPORTUNITY_SEED, id], &program_id()).0
}

fn wallet(k: &Keypair) -> Pubkey {
    pk(&k.pubkey())
}

impl Env {
    fn new() -> Self {
        let mut env = Self::without_config();
        env.init_config(&env.admin.insecure_clone()).unwrap();
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
        set_upgrade_authority(&mut svm, &wallet(&admin));
        set_time(&mut svm, NOW);

        let mint = Pubkey::new_unique();
        put(&mut svm, &mint, skr_mint(), &spl_token::ID);

        let group = Pubkey::new_unique();
        let placeholder = Seeker { wallet: Keypair::new(), sgt: Pubkey::default(), sgt_account: Pubkey::default() };
        let mut env = Env { svm, admin, sponsor, seeker: placeholder, mint, group, next_id: 0 };
        env.seeker = env.new_seeker();
        env.give(&wallet(&env.sponsor), 10_000 * SKR);
        env
    }

    fn new_seeker(&mut self) -> Seeker {
        let wallet = Keypair::new();
        self.svm.airdrop(&wallet.pubkey(), 1_000_000_000).unwrap();
        let sgt = Pubkey::new_unique();
        put(&mut self.svm, &sgt, sgt_mint(&sgt, &sgt, &self.group), &TOKEN_2022);
        let sgt_account = self.token_account(&sgt, &pk(&wallet.pubkey()), 1, &TOKEN_2022);
        Seeker { wallet, sgt, sgt_account }
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

    fn vault(&self, id: &[u8; 16]) -> Pubkey {
        get_associated_token_address(&opportunity_pda(id), &self.mint)
    }

    fn lamports(&self, key: &Pubkey) -> u64 {
        self.svm.get_account(&addr(key)).map_or(0, |a| a.lamports)
    }

    fn exists(&self, key: &Pubkey) -> bool {
        self.lamports(key) > 0
    }

    fn opportunity(&self, id: &[u8; 16]) -> Opportunity {
        read(&self.svm, &opportunity_pda(id))
    }

    fn send(&mut self, ix: Instruction, signers: &[&Keypair]) -> Result<(), String> {
        self.svm.expire_blockhash();
        let msg = Message::new_with_blockhash(&[ix], Some(&signers[0].pubkey()), &self.svm.latest_blockhash());
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(msg), signers).unwrap();
        self.svm.send_transaction(tx).map(|_| ()).map_err(|e| format!("{:?}", e.err))
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

    fn init_config(&mut self, signer: &Keypair) -> Result<(), String> {
        let program_data = Pubkey::find_program_address(&[program_id().as_ref()], &bpf_loader_upgradeable()).0;
        let ix = self.ix(
            instruction::InitConfig { sgt_group: self.group },
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

    fn set_sgt_group(&mut self, signer: &Keypair, sgt_group: Pubkey) -> Result<(), String> {
        let ix = self.ix(instruction::SetSgtGroup { sgt_group }, accounts::AdminOnly { admin: wallet(signer), config: config_pda() });
        self.send(ix, &[signer])
    }

    fn lock(&mut self, amount: u64, players: u8, owner_bps: u16) -> Lock {
        self.next_id += 1;
        let mut id = *b"formation-000000";
        id[15] = self.next_id;
        Lock {
            id,
            amount,
            players,
            owner_bps,
            difficulty: 1,
            title: title("Genesis Rally"),
            expires_at: NOW + DAY,
            seeker: wallet(&self.seeker.wallet),
            sgt: self.seeker.sgt,
            sgt_account: self.seeker.sgt_account,
            mint: self.mint,
        }
    }

    fn create(&mut self, lock: &Lock) -> Result<(), String> {
        let sponsor = wallet(&self.sponsor);
        let opportunity = opportunity_pda(&lock.id);
        let ix = self.ix(
            instruction::Create {
                id: lock.id,
                amount: lock.amount,
                players: lock.players,
                owner_bps: lock.owner_bps,
                challenge: RALLY,
                difficulty: lock.difficulty,
                title: lock.title,
                expires_at: lock.expires_at,
            },
            accounts::Create {
                sponsor,
                config: config_pda(),
                seeker: lock.seeker,
                sgt_mint: lock.sgt,
                sgt_account: lock.sgt_account,
                opportunity,
                vault: get_associated_token_address(&opportunity, &lock.mint),
                sponsor_token: get_associated_token_address(&sponsor, &lock.mint),
                mint: lock.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        );
        let sponsor = self.sponsor.insecure_clone();
        self.send(ix, &[&sponsor])
    }

    fn unlock(&mut self, signer: &Keypair, id: &[u8; 16], roster_root: [u8; 32], roster_size: u8) -> Result<(), String> {
        let ix = self.unlock_ix(signer, id, roster_root, roster_size);
        self.send(ix, &[signer])
    }

    fn unlock_ix(&self, signer: &Keypair, id: &[u8; 16], roster_root: [u8; 32], roster_size: u8) -> Instruction {
        let seeker = wallet(signer);
        self.ix(
            instruction::Unlock { roster_root, roster_size, result: RESULT },
            accounts::Unlock {
                seeker,
                opportunity: opportunity_pda(id),
                vault: self.vault(id),
                seeker_token: get_associated_token_address(&seeker, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        )
    }

    fn claim(&mut self, payer: &Keypair, claimer: &Keypair, recipient: &Pubkey, id: &[u8; 16], index: u8, proof: Vec<[u8; 32]>) -> Result<(), String> {
        let mut ix = self.claim_ix(payer, &wallet(claimer), recipient, id, index, proof);
        ix.accounts[1].is_signer = true;
        self.send(ix, &[payer, claimer])
    }

    fn crank(&mut self, payer: &Keypair, claimer: &Pubkey, recipient: &Pubkey, id: &[u8; 16], index: u8, proof: Vec<[u8; 32]>) -> Result<(), String> {
        let ix = self.claim_ix(payer, claimer, recipient, id, index, proof);
        self.send(ix, &[payer])
    }

    fn claim_ix(&self, payer: &Keypair, claimer: &Pubkey, recipient: &Pubkey, id: &[u8; 16], index: u8, proof: Vec<[u8; 32]>) -> Instruction {
        self.ix(
            instruction::Claim { index, proof },
            accounts::Claim {
                payer: wallet(payer),
                claimer: *claimer,
                recipient: *recipient,
                opportunity: opportunity_pda(id),
                vault: self.vault(id),
                recipient_token: get_associated_token_address(recipient, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        )
    }

    fn send_all(&mut self, ixs: &[Instruction], signers: &[&Keypair]) -> Result<usize, String> {
        self.svm.expire_blockhash();
        let msg = Message::new_with_blockhash(ixs, Some(&signers[0].pubkey()), &self.svm.latest_blockhash());
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(msg), signers).unwrap();
        let size = bincode_size(&tx);
        self.svm.send_transaction(tx).map(|_| size).map_err(|e| format!("{:?}", e.err))
    }

    fn close(&mut self, payer: &Keypair, id: &[u8; 16]) -> Result<(), String> {
        let sponsor = wallet(&self.sponsor);
        self.close_to(payer, id, &sponsor)
    }

    fn close_to(&mut self, payer: &Keypair, id: &[u8; 16], sponsor: &Pubkey) -> Result<(), String> {
        let ix = self.ix(
            instruction::Close {},
            accounts::Close {
                payer: wallet(payer),
                sponsor: *sponsor,
                opportunity: opportunity_pda(id),
                vault: self.vault(id),
                sponsor_token: get_associated_token_address(sponsor, &self.mint),
                mint: self.mint,
                token_program: spl_token::ID,
                associated_token_program: associated_token::ID,
                system_program: system_program(),
            },
        );
        self.send(ix, &[payer])
    }

    fn seeker_wallet(&self) -> Keypair {
        self.seeker.wallet.insecure_clone()
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
        supply: 1_000_000 * SKR,
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

fn bincode_size(tx: &VersionedTransaction) -> usize {
    let sigs = tx.signatures.len();
    1 + sigs * 64 + tx.message.serialize().len()
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

fn expect_err(result: Result<(), String>, code: u32) {
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
        assert_eq!(hex(Roster::new(&reference_keys(n)).root()), expected, "{n} helpers");
    }
    // Even helpers bound to a wallet of 32 bytes of 0xA0 + i, odd ones unbound.
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
            assert_eq!(formation_vault::roster_root(i as u8, key, &UNBOUND, &proof), roster.root(), "helper {i} of {n}");
        }
    }
}

#[test]
fn splits_match_the_app() {
    assert_eq!(split(600 * SKR, 5_000, 4).unwrap(), (300 * SKR, 75 * SKR));
    assert_eq!(split(100 * SKR, 5_000, 3).unwrap(), (50 * SKR + 2, 16_666_666));
    assert_eq!(split(7, 0, 2).unwrap(), (1, 3));
    assert_eq!(split(u64::MAX, 9_999, 64).unwrap().0 + split(u64::MAX, 9_999, 64).unwrap().1 * 64, u64::MAX);
    assert!(split(100, 5_000, 0).is_err());
}

#[test]
fn config_needs_the_upgrade_authority() {
    let mut env = Env::without_config();
    let intruder = env.user();
    expect_err(env.init_config(&intruder), code(VaultError::Unauthorized));

    let admin = env.admin.insecure_clone();
    env.init_config(&admin).unwrap();
    let config: Config = read(&env.svm, &config_pda());
    assert_eq!(config.admin, wallet(&admin));
    assert_eq!(config.mint, env.mint);
    assert_eq!(config.sgt_group, env.group);
    expect_err(env.init_config(&admin), ALREADY_IN_USE);

    let group = Pubkey::new_unique();
    expect_err(env.set_sgt_group(&intruder, group), code(VaultError::Unauthorized));
    env.set_sgt_group(&admin, group).unwrap();
    assert_eq!(read::<Config>(&env.svm, &config_pda()).sgt_group, group);
}

#[test]
fn a_formation_unlocks_the_reward_for_everyone() {
    let mut env = Env::new();
    let lock = env.lock(600 * SKR, 5, 5_000);
    env.create(&lock).unwrap();

    let o = env.opportunity(&lock.id);
    assert_eq!(o.state, OpportunityState::Open);
    assert_eq!((o.amount, o.players, o.owner_bps, o.challenge, o.difficulty), (600 * SKR, 5, 5_000, RALLY, 1));
    assert_eq!(&o.title[..13], b"Genesis Rally");
    assert!(o.title[13..].iter().all(|&b| b == 0));
    assert_eq!((o.seeker, o.sgt, o.sponsor), (lock.seeker, lock.sgt, wallet(&env.sponsor)));
    assert_eq!(env.balance(&wallet(&env.sponsor)), 9_400 * SKR);
    assert_eq!(env.token_balance(&env.vault(&lock.id)), 600 * SKR);
    expect_err(env.create(&lock), ALREADY_IN_USE);

    let helpers: Vec<Keypair> = (0..4).map(|_| Keypair::new()).collect();
    let roster = Roster::new(&helpers.iter().map(wallet).collect::<Vec<_>>());
    let seeker = env.seeker_wallet();
    env.unlock(&seeker, &lock.id, roster.root(), 4).unwrap();
    assert_eq!(env.balance(&wallet(&seeker)), 300 * SKR);
    let o = env.opportunity(&lock.id);
    assert_eq!(o.state, OpportunityState::Unlocked);
    assert_eq!((o.roster_root, o.roster_size, o.helper_share, o.result, o.unlocked_at), (roster.root(), 4, 75 * SKR, RESULT, NOW));
    expect_err(env.unlock(&seeker, &lock.id, roster.root(), 4), code(VaultError::NotOpen));

    for (i, helper) in helpers.iter().enumerate() {
        let recipient = Pubkey::new_unique();
        env.claim(&seeker, helper, &recipient, &lock.id, i as u8, roster.proof(i)).unwrap();
        assert_eq!(env.balance(&recipient), 75 * SKR);
    }
    assert_eq!(env.opportunity(&lock.id).claimed, 0b1111);
    assert_eq!(env.token_balance(&env.vault(&lock.id)), 0);

    let before = env.lamports(&wallet(&env.sponsor));
    env.close(&seeker, &lock.id).unwrap();
    assert!(!env.exists(&opportunity_pda(&lock.id)));
    assert!(!env.exists(&env.vault(&lock.id)));
    assert!(env.lamports(&wallet(&env.sponsor)) > before);
    assert_eq!(env.balance(&wallet(&env.sponsor)), 9_400 * SKR);
}

#[test]
fn the_seeker_keeps_the_rounding_dust() {
    let mut env = Env::new();
    let lock = env.lock(100 * SKR, 4, 5_000);
    env.create(&lock).unwrap();
    let roster = Roster::new(&reference_keys(3));
    let seeker = env.seeker_wallet();
    env.unlock(&seeker, &lock.id, roster.root(), 3).unwrap();
    assert_eq!(env.balance(&wallet(&seeker)), 50 * SKR + 2);
    assert_eq!(env.opportunity(&lock.id).helper_share, 16_666_666);
    assert_eq!(env.token_balance(&env.vault(&lock.id)), 3 * 16_666_666);
}

#[test]
fn only_a_seeker_genesis_token_counts() {
    let mut env = Env::new();
    let seeker = wallet(&env.seeker.wallet);
    let base = env.lock(600 * SKR, 5, 5_000);

    let other_group = Pubkey::new_unique();
    let fake = Pubkey::new_unique();
    put(&mut env.svm, &fake, sgt_mint(&fake, &fake, &other_group), &TOKEN_2022);
    let fake_account = env.token_account(&fake, &seeker, 1, &TOKEN_2022);
    let lock = Lock { sgt: fake, sgt_account: fake_account, ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));

    let copy = Pubkey::new_unique();
    put(&mut env.svm, &copy, sgt_mint(&copy, &env.seeker.sgt, &env.group), &TOKEN_2022);
    let copy_account = env.token_account(&copy, &seeker, 1, &TOKEN_2022);
    let lock = Lock { sgt: copy, sgt_account: copy_account, ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));

    let classic = Pubkey::new_unique();
    put(&mut env.svm, &classic, sgt_mint(&classic, &classic, &env.group), &spl_token::ID);
    let classic_account = env.token_account(&classic, &seeker, 1, &TOKEN_2022);
    let lock = Lock { sgt: classic, sgt_account: classic_account, ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));

    let plain = Pubkey::new_unique();
    let mut data = vec![0; spl_token::state::Mint::LEN];
    spl_token::state::Mint { supply: 1, is_initialized: true, ..Default::default() }.pack_into_slice(&mut data);
    put(&mut env.svm, &plain, data, &TOKEN_2022);
    let plain_account = env.token_account(&plain, &seeker, 1, &TOKEN_2022);
    let lock = Lock { sgt: plain, sgt_account: plain_account, ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));

    let stranger = env.user();
    let lock = Lock { seeker: wallet(&stranger), ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));
    let empty = env.token_account(&env.seeker.sgt.clone(), &seeker, 0, &TOKEN_2022);
    let lock = Lock { sgt_account: empty, ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));
    let forged = env.token_account(&env.seeker.sgt.clone(), &seeker, 1, &spl_token::ID);
    let lock = Lock { sgt_account: forged, ..base.clone() };
    expect_err(env.create(&lock), code(VaultError::NotASeeker));

    env.create(&base).unwrap();
    let admin = env.admin.insecure_clone();
    env.set_sgt_group(&admin, other_group).unwrap();
    let lock = Lock { sgt: fake, sgt_account: fake_account, ..env.lock(600 * SKR, 5, 5_000) };
    env.create(&lock).unwrap();
}

#[test]
fn opportunities_must_make_sense() {
    let mut env = Env::new();
    let base = env.lock(600 * SKR, 5, 5_000);
    expect_err(env.create(&Lock { amount: 0, ..base.clone() }), code(VaultError::ZeroAmount));
    expect_err(env.create(&Lock { players: 1, ..base.clone() }), code(VaultError::BadPlayers));
    expect_err(env.create(&Lock { players: 33, ..base.clone() }), code(VaultError::BadPlayers));
    expect_err(env.create(&Lock { owner_bps: 10_000, ..base.clone() }), code(VaultError::BadShare));
    expect_err(env.create(&Lock { expires_at: NOW, ..base.clone() }), code(VaultError::AlreadyExpired));
    expect_err(env.create(&Lock { difficulty: 4, ..base.clone() }), code(VaultError::BadDifficulty));

    let other = Pubkey::new_unique();
    put(&mut env.svm, &other, skr_mint(), &spl_token::ID);
    let sponsor = wallet(&env.sponsor);
    put(&mut env.svm, &get_associated_token_address(&sponsor, &other), token_account(&other, &sponsor, 1_000 * SKR), &spl_token::ID);
    expect_err(env.create(&Lock { mint: other, ..base.clone() }), code(VaultError::WrongMint));

    env.create(&Lock { players: 2, owner_bps: 0, ..base.clone() }).unwrap();
    let lock = env.lock(600 * SKR, 32, 9_999);
    env.create(&lock).unwrap();
}

#[test]
fn only_the_seeker_unlocks_and_only_with_enough_helpers() {
    let mut env = Env::new();
    let lock = env.lock(600 * SKR, 5, 5_000);
    env.create(&lock).unwrap();
    let root = Roster::new(&reference_keys(6)).root();

    let intruder = env.user();
    expect_err(env.unlock(&intruder, &lock.id, root, 4), code(VaultError::Unauthorized));
    let other = env.new_seeker();
    expect_err(env.unlock(&other.wallet, &lock.id, root, 4), code(VaultError::Unauthorized));

    let seeker = env.seeker_wallet();
    expect_err(env.unlock(&seeker, &lock.id, root, 3), code(VaultError::RosterSize));
    expect_err(env.unlock(&seeker, &lock.id, root, 65), code(VaultError::RosterSize));

    env.unlock(&seeker, &lock.id, root, 6).unwrap();
    assert_eq!(env.opportunity(&lock.id).helper_share, 50 * SKR);
    assert_eq!(env.balance(&wallet(&seeker)), 300 * SKR);
}

#[test]
fn a_claim_needs_the_claim_key_and_its_proof() {
    let mut env = Env::new();
    let lock = env.lock(600 * SKR, 5, 5_000);
    env.create(&lock).unwrap();
    let helpers: Vec<Keypair> = (0..4).map(|_| Keypair::new()).collect();
    let roster = Roster::new(&helpers.iter().map(wallet).collect::<Vec<_>>());
    let payer = env.user();
    let recipient = Pubkey::new_unique();

    expect_err(env.claim(&payer, &helpers[0], &recipient, &lock.id, 0, roster.proof(0)), code(VaultError::NotUnlocked));
    let seeker = env.seeker_wallet();
    env.unlock(&seeker, &lock.id, roster.root(), 4).unwrap();

    let stranger = Keypair::new();
    expect_err(env.claim(&payer, &stranger, &recipient, &lock.id, 0, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &helpers[0], &recipient, &lock.id, 1, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &helpers[0], &recipient, &lock.id, 0, roster.proof(1)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &helpers[0], &recipient, &lock.id, 4, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&payer, &helpers[0], &recipient, &lock.id, 0, vec![[0; 32]; 8]), code(VaultError::ProofTooLong));

    env.claim(&payer, &helpers[0], &recipient, &lock.id, 0, roster.proof(0)).unwrap();
    assert_eq!(env.balance(&recipient), 75 * SKR);
    expect_err(env.claim(&payer, &helpers[0], &Pubkey::new_unique(), &lock.id, 0, roster.proof(0)), code(VaultError::AlreadyClaimed));

    env.claim(&payer, &helpers[1], &wallet(&helpers[1]), &lock.id, 1, roster.proof(1)).unwrap();
    assert_eq!(env.balance(&wallet(&helpers[1])), 75 * SKR);
    assert_eq!(env.opportunity(&lock.id).claimed, 0b11);
}

#[test]
fn an_expired_opportunity_goes_back_to_the_sponsor() {
    let mut env = Env::new();
    let lock = env.lock(600 * SKR, 5, 5_000);
    env.create(&lock).unwrap();
    let anyone = env.user();
    expect_err(env.close(&anyone, &lock.id), code(VaultError::NotClosable));

    set_time(&mut env.svm, lock.expires_at);
    let seeker = env.seeker_wallet();
    expect_err(env.unlock(&seeker, &lock.id, Roster::new(&reference_keys(4)).root(), 4), code(VaultError::Expired));
    let stranger = wallet(&env.user());
    expect_err(env.close_to(&anyone, &lock.id, &stranger), HAS_ONE);

    env.close(&anyone, &lock.id).unwrap();
    assert_eq!(env.balance(&wallet(&env.sponsor)), 10_000 * SKR);
    assert!(!env.exists(&opportunity_pda(&lock.id)));
    assert!(!env.exists(&env.vault(&lock.id)));
}

#[test]
fn unclaimed_shares_go_back_after_the_claim_window() {
    let mut env = Env::new();
    let lock = env.lock(600 * SKR, 5, 5_000);
    env.create(&lock).unwrap();
    let helpers: Vec<Keypair> = (0..4).map(|_| Keypair::new()).collect();
    let roster = Roster::new(&helpers.iter().map(wallet).collect::<Vec<_>>());
    let seeker = env.seeker_wallet();
    env.unlock(&seeker, &lock.id, roster.root(), 4).unwrap();
    env.claim(&seeker, &helpers[0], &wallet(&helpers[0]), &lock.id, 0, roster.proof(0)).unwrap();

    set_time(&mut env.svm, NOW + CLAIM_WINDOW - 1);
    expect_err(env.close(&seeker, &lock.id), code(VaultError::NotClosable));
    env.claim(&seeker, &helpers[1], &wallet(&helpers[1]), &lock.id, 1, roster.proof(1)).unwrap();

    set_time(&mut env.svm, NOW + CLAIM_WINDOW);
    expect_err(env.claim(&seeker, &helpers[2], &wallet(&helpers[2]), &lock.id, 2, roster.proof(2)), code(VaultError::ClaimWindowClosed));
    env.close(&seeker, &lock.id).unwrap();
    assert_eq!(env.balance(&wallet(&env.sponsor)), 9_400 * SKR + 150 * SKR);
    assert!(!env.exists(&opportunity_pda(&lock.id)));
}

#[test]
fn a_real_seeker_genesis_token_counts() {
    // Copied from mainnet: the mint has other extensions before the member entry, and the holder's
    // account is frozen with an immutable owner.
    let mut env = Env::new();
    let group = Pubkey::from_str_const("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
    let sgt = Pubkey::from_str_const("JBbD5StDRA4rah3yMYUPEXhs8A9mW7mbnqcvzeMo4dZR");
    let sgt_account = Pubkey::from_str_const("EduidFzPEXBhMsJQM8GL3kFXRxXurngCRG2VSV1apdvT");
    let holder = Pubkey::from_str_const("634xbmD6cxgWDrniqXTAb2VnziBfhC3Xfii5pMp42GpV");
    put(&mut env.svm, &sgt, include_bytes!("fixtures/sgt-mint.bin").to_vec(), &TOKEN_2022);
    put(&mut env.svm, &sgt_account, include_bytes!("fixtures/sgt-account.bin").to_vec(), &TOKEN_2022);
    let lock = Lock { seeker: holder, sgt, sgt_account, ..env.lock(600 * SKR, 5, 5_000) };

    expect_err(env.create(&lock), code(VaultError::NotASeeker));
    let admin = env.admin.insecure_clone();
    env.set_sgt_group(&admin, group).unwrap();
    env.create(&lock).unwrap();
    let o = env.opportunity(&lock.id);
    assert_eq!((o.seeker, o.sgt), (holder, sgt));
}

#[test]
fn bound_shares_land_in_the_helpers_wallet_without_their_signature() {
    let mut env = Env::new();
    let lock = env.lock(600 * SKR, 5, 5_000);
    env.create(&lock).unwrap();
    let helpers: Vec<Keypair> = (0..4).map(|_| Keypair::new()).collect();
    let wallets: Vec<Pubkey> = (0..4).map(|_| Pubkey::new_unique()).collect();
    let entries: Vec<(Pubkey, [u8; 32])> =
        helpers.iter().enumerate().map(|(i, h)| (wallet(h), if i % 2 == 0 { wallets[i].to_bytes() } else { UNBOUND })).collect();
    let roster = Roster::with_wallets(&entries);
    let seeker = env.seeker_wallet();
    env.unlock(&seeker, &lock.id, roster.root(), 4).unwrap();

    let stranger = Pubkey::new_unique();
    expect_err(env.crank(&seeker, &wallet(&helpers[0]), &stranger, &lock.id, 0, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.claim(&seeker, &helpers[0], &stranger, &lock.id, 0, roster.proof(0)), code(VaultError::NotInRoster));
    expect_err(env.crank(&seeker, &wallet(&helpers[1]), &stranger, &lock.id, 1, roster.proof(1)), code(VaultError::NotInRoster));

    env.crank(&seeker, &wallet(&helpers[0]), &wallets[0], &lock.id, 0, roster.proof(0)).unwrap();
    env.crank(&seeker, &wallet(&helpers[2]), &wallets[2], &lock.id, 2, roster.proof(2)).unwrap();
    assert_eq!(env.balance(&wallets[0]), 75 * SKR);
    assert_eq!(env.balance(&wallets[2]), 75 * SKR);
    expect_err(env.crank(&seeker, &wallet(&helpers[0]), &wallets[0], &lock.id, 0, roster.proof(0)), code(VaultError::AlreadyClaimed));

    env.claim(&seeker, &helpers[1], &stranger, &lock.id, 1, roster.proof(1)).unwrap();
    assert_eq!(env.balance(&stranger), 75 * SKR);
    assert_eq!(env.opportunity(&lock.id).claimed, 0b0111);
}

#[test]
fn the_unlock_and_every_payout_fit_one_transaction() {
    let mut env = Env::new();
    let lock = env.lock(900 * SKR, 4, 4_000);
    env.create(&lock).unwrap();
    let helpers: Vec<Pubkey> = (0..3).map(|_| Pubkey::new_unique()).collect();
    let wallets: Vec<Pubkey> = (0..3).map(|_| Pubkey::new_unique()).collect();
    let roster = Roster::with_wallets(&helpers.iter().zip(&wallets).map(|(h, w)| (*h, w.to_bytes())).collect::<Vec<_>>());
    let seeker = env.seeker_wallet();
    let mut ixs = vec![env.unlock_ix(&seeker, &lock.id, roster.root(), 3)];
    for i in 0..3 {
        ixs.push(env.claim_ix(&seeker, &helpers[i], &wallets[i], &lock.id, i as u8, roster.proof(i)));
    }
    let size = env.send_all(&ixs, &[&seeker]).unwrap();
    assert!(size <= 1_232, "{size} bytes");
    assert_eq!(env.balance(&wallet(&seeker)), 360 * SKR);
    for w in &wallets {
        assert_eq!(env.balance(w), 180 * SKR);
    }
}
