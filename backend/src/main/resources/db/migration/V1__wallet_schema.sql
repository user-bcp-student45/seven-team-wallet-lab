-- Esquema inicial del MVP. PostgreSQL es la unica fuente financiera.

create table app_user (
    id                 uuid primary key,
    email              varchar(254) not null,
    password_hash      varchar(255) not null,
    full_name          varchar(120),
    document_number    char(8),
    phone              char(9),
    phone_verified     boolean      not null default false,
    phone_code_hash    char(64),
    phone_code_expires timestamptz,
    phone_code_attempts smallint    not null default 0,
    status             varchar(12)  not null default 'ONBOARDING',
    completed_steps    smallint     not null default 1,
    failed_logins      smallint     not null default 0,
    locked_until       timestamptz,
    demo_contact       boolean      not null default false,
    created_at         timestamptz  not null default now(),
    activated_at       timestamptz,
    constraint ck_user_status check (status in ('ONBOARDING', 'ACTIVE')),
    constraint ck_user_steps check (completed_steps between 0 and 4),
    constraint ck_user_dni check (document_number is null or document_number ~ '^[0-9]{8}$'),
    constraint ck_user_phone check (phone is null or phone ~ '^9[0-9]{8}$')
);
create unique index ux_app_user_email on app_user (lower(email));
create unique index ux_app_user_dni on app_user (document_number) where document_number is not null;

create table onboarding_event (
    id             bigserial primary key,
    user_id        uuid        not null references app_user (id),
    event_type     varchar(30) not null,
    step           smallint,
    correlation_id varchar(64),
    occurred_at    timestamptz not null default now(),
    constraint ck_onboarding_event_type check (event_type in ('ONBOARDING_STARTED', 'STEP_COMPLETED', 'ONBOARDING_COMPLETED'))
);
create index ix_onboarding_event_user on onboarding_event (user_id, occurred_at);

create table account (
    id            uuid primary key,
    owner_user_id uuid references app_user (id),
    type          varchar(10) not null,
    currency      char(3)     not null,
    created_at    timestamptz not null default now(),
    constraint ck_account_type check (type in ('WALLET', 'SYSTEM')),
    constraint ck_account_owner check ((type = 'WALLET' and owner_user_id is not null) or (type = 'SYSTEM' and owner_user_id is null))
);
create unique index ux_account_wallet_owner on account (owner_user_id) where type = 'WALLET';

create table ledger_transaction (
    id         uuid primary key,
    kind       varchar(10) not null,
    reference  varchar(64) not null unique,
    created_at timestamptz not null default now(),
    constraint ck_ledger_tx_kind check (kind in ('SEED', 'TRANSFER'))
);

create table ledger_entry (
    id             bigserial primary key,
    transaction_id uuid          not null references ledger_transaction (id),
    account_id     uuid          not null references account (id),
    direction      varchar(6)    not null,
    amount         numeric(19,2) not null,
    currency       char(3)       not null,
    created_at     timestamptz   not null default now(),
    constraint ck_entry_direction check (direction in ('DEBIT', 'CREDIT')),
    constraint ck_entry_amount check (amount > 0)
);
create index ix_ledger_entry_account on ledger_entry (account_id);
create index ix_ledger_entry_tx on ledger_entry (transaction_id);

-- Asientos inmutables.
create function ledger_entry_immutable() returns trigger as $$
begin
    raise exception 'ledger_entry es inmutable';
end;
$$ language plpgsql;

create trigger trg_ledger_entry_immutable
    before update or delete on ledger_entry
    for each row execute function ledger_entry_immutable();

-- Doble partida: por transaccion y moneda, debitos = creditos al confirmar.
create function ledger_transaction_balanced() returns trigger as $$
declare
    unbalanced integer;
begin
    select count(*) into unbalanced from (
        select currency
        from ledger_entry
        where transaction_id = new.transaction_id
        group by currency
        having sum(case direction when 'DEBIT' then amount else -amount end) <> 0
    ) t;
    if unbalanced > 0 then
        raise exception 'asientos desbalanceados en la transaccion %', new.transaction_id;
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger trg_ledger_balanced
    after insert on ledger_entry
    deferrable initially deferred
    for each row execute function ledger_transaction_balanced();

create table contact (
    owner_user_id   uuid        not null references app_user (id),
    contact_user_id uuid        not null references app_user (id),
    created_at      timestamptz not null default now(),
    primary key (owner_user_id, contact_user_id),
    constraint ck_contact_not_self check (owner_user_id <> contact_user_id)
);

create table payment_intent (
    id                  uuid primary key,
    payer_id            uuid          not null references app_user (id),
    destination_user_id uuid          not null references app_user (id),
    amount              numeric(19,2) not null,
    currency            char(3)       not null,
    idempotency_key     char(64)      not null,
    created_at          timestamptz   not null default now(),
    expires_at          timestamptz   not null,
    constraint ck_intent_amount check (amount > 0),
    constraint ux_intent_key unique (payer_id, idempotency_key)
);

create table payment (
    id              uuid primary key,
    reference       varchar(14)   not null unique,
    intent_id       uuid          not null references payment_intent (id),
    payer_id        uuid          not null references app_user (id),
    payee_id        uuid          not null references app_user (id),
    amount          numeric(19,2) not null,
    currency        char(3)       not null,
    status          varchar(10)   not null,
    failure_code    varchar(30),
    idempotency_key char(64)      not null,
    fingerprint     char(64)      not null,
    created_at      timestamptz   not null default now(),
    constraint ck_payment_status check (status in ('SUCCESS', 'FAILED', 'EXPIRED')),
    constraint ck_payment_amount check (amount > 0),
    -- Garantia de idempotencia: una sola transaccion financiera por pagador y clave.
    constraint ux_payment_key unique (payer_id, idempotency_key),
    constraint ux_payment_intent unique (intent_id)
);
create index ix_payment_payer on payment (payer_id, created_at desc);
create index ix_payment_payee on payment (payee_id, created_at desc);

create table payment_attempt (
    id              uuid primary key,
    payment_id      uuid        not null references payment (id),
    payer_id        uuid        not null references app_user (id),
    idempotency_key char(64)    not null,
    kind            varchar(10) not null,
    outcome         varchar(10) not null,
    request_id      varchar(64) not null,
    correlation_id  varchar(64) not null,
    created_at      timestamptz not null default now(),
    constraint ck_attempt_kind check (kind in ('ORIGINAL', 'DUPLICATE'))
);
create index ix_payment_attempt_payer on payment_attempt (payer_id, created_at desc);
create index ix_payment_attempt_payment on payment_attempt (payment_id);

create table outbox_event (
    id             bigserial primary key,
    aggregate_type varchar(30) not null,
    aggregate_id   uuid        not null,
    event_type     varchar(40) not null,
    payload        jsonb       not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz
);

-- Salida analitica HU-01 CA6 (conteos mensuales y mediana de duracion). El gasto de adquisicion no existe aun.
create view v_onboarding_monthly as
select date_trunc('month', done.occurred_at) as month,
       count(*)                                as new_active_users,
       percentile_cont(0.5) within group (order by extract(epoch from (done.occurred_at - started.occurred_at))) as median_seconds
from onboarding_event done
join onboarding_event started
  on started.user_id = done.user_id and started.event_type = 'ONBOARDING_STARTED'
where done.event_type = 'ONBOARDING_COMPLETED'
group by 1;
