-- A group's ledger holds one GROUP_MEMBER account per member: what the group owes them, positive, or
-- what they owe it, negative (ADR-0012). V4 left the kind out until groups came (#83).
--
-- The check keeps V4's default name, so a later change finds it where V4 put it. Checking the
-- existing rows is quick: no account can be a GROUP_MEMBER before this migration.
ALTER TABLE accounts DROP CONSTRAINT accounts_kind_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_kind_check
    CHECK (kind IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY', 'GROUP_MEMBER'));
