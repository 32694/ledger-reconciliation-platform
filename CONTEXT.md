# Ledger Reconciliation Platform

This context models a simulated payment institution that maintains customer wallets, records balanced ledger transactions, and compares local payments with channel statements.

## Language

### Accounts and money

**Customer Account**:
The operational account presented to an administrator for a customer wallet. It has an account number, owner name, status, and currency, but does not store an independently editable balance.
_Avoid_: User account, bank account, ledger account

**Ledger Account**:
An accounting account classified as an asset or liability and referenced by ledger entries. A customer wallet is represented by a liability ledger account.
_Avoid_: Customer account, balance account

**Available Balance**:
The amount a customer can transfer, derived from posted ledger entries. For a customer wallet, it is total credits minus total debits.
_Avoid_: Stored balance, cached balance

**Money**:
A positive amount in one currency, represented in the smallest currency unit. The first release supports CNY only.
_Avoid_: Decimal amount, floating-point amount

### Payments and ledger

**Payment Instruction**:
A request to top up or transfer money, uniquely identified for retry by an idempotency key.
_Avoid_: Order, payment transaction, ledger transaction

**Ledger Transaction**:
An immutable, balanced group of ledger entries created for one business reference.
_Avoid_: Payment, channel record

**Ledger Entry**:
One immutable debit or credit posted to a ledger account as part of a ledger transaction.
_Avoid_: Transaction, balance change

**Top-up**:
A simulated inflow that debits the platform cash asset and credits a customer wallet liability.
_Avoid_: Deposit, recharge

**Transfer**:
A movement between customer wallets that debits the payer wallet and credits the payee wallet.
_Avoid_: Remittance, payment

### Reconciliation

**Channel Statement**:
A CSV file containing records reported by a simulated external payment channel.
_Avoid_: Bank statement, reconciliation file

**Channel Record**:
One imported row identified by a channel transaction ID, amount, and occurrence time.
_Avoid_: Local payment, ledger entry

**Reconciliation Batch**:
One immutable import and comparison run for a channel statement.
_Avoid_: Upload job, statement

**Reconciliation Result**:
The comparison outcome between local payments and channel records: matched, local-only, channel-only, amount-mismatch, or duplicate-channel-record.
_Avoid_: Error, exception

**Resolution**:
An administrator's recorded disposition of a reconciliation difference. Resolution does not rewrite the original payment, channel record, or ledger entries.
_Avoid_: Fix, deletion

