-- The known name a category is called by (#79), which the app shows in each person's language: the
-- key of a name from the app's list (KnownName, the API's CategoryKey). A category without a row is
-- called by its account's name, as the person typed it. The table belongs to `personal`, as
-- category_icons does. The account of a category with a known name keeps the English name, so the
-- rule against repeated names (V8) compares known names in one language.
--
-- A default category's subtype still says which default it is; its row says what it is called, which
-- changes when the person renames it.
CREATE TABLE category_keys (
    category_id uuid PRIMARY KEY REFERENCES accounts (id),
    key         text NOT NULL CONSTRAINT category_keys_key_check CHECK (key IN (
        'housing', 'food', 'transport', 'bills', 'shopping', 'leisure', 'health', 'other', 'salary',
        'refunds', 'gifts', 'other-income', 'groceries', 'restaurants', 'rent', 'condo-fees',
        'electricity', 'water', 'gas', 'phone-internet', 'subscriptions', 'fuel', 'tolls',
        'insurance', 'pharmacy', 'personal-care', 'clothes', 'gym', 'education', 'kids', 'pets',
        'travel', 'gifts-given', 'taxes', 'freelance', 'bonus', 'interest', 'dividends',
        'holiday-allowance', 'christmas-allowance', 'sales', 'pension'))
);

-- Every category that already has a name from the list, in English or in Portuguese and whatever its
-- case, gets that key: the defaults nobody renamed, and the ones people created or renamed. The
-- account takes the English name. A Portuguese name becomes the English one only when no other
-- active category of the ledger and kind already has it: "Alimentação" next to the default "Food"
-- stays as typed, rather than break the rule against repeated names. Archived categories may share
-- names, so they always convert.
WITH known (key, kind, english, portuguese) AS (
    VALUES ('housing', 'EXPENSE', 'Housing', 'Casa'),
           ('food', 'EXPENSE', 'Food', 'Alimentação'),
           ('transport', 'EXPENSE', 'Transport', 'Transportes'),
           ('bills', 'EXPENSE', 'Bills', 'Faturas'),
           ('shopping', 'EXPENSE', 'Shopping', 'Compras'),
           ('leisure', 'EXPENSE', 'Leisure', 'Lazer'),
           ('health', 'EXPENSE', 'Health', 'Saúde'),
           ('other', 'EXPENSE', 'Other', 'Outros'),
           ('salary', 'INCOME', 'Salary', 'Salário'),
           ('refunds', 'INCOME', 'Refunds', 'Reembolsos'),
           ('gifts', 'INCOME', 'Gifts', 'Presentes'),
           ('other-income', 'INCOME', 'Other income', 'Outras receitas'),
           ('groceries', 'EXPENSE', 'Groceries', 'Supermercado'),
           ('restaurants', 'EXPENSE', 'Restaurants', 'Restaurantes'),
           ('rent', 'EXPENSE', 'Rent', 'Renda'),
           ('condo-fees', 'EXPENSE', 'Condo fees', 'Condomínio'),
           ('electricity', 'EXPENSE', 'Electricity', 'Luz'),
           ('water', 'EXPENSE', 'Water', 'Água'),
           ('gas', 'EXPENSE', 'Gas', 'Gás'),
           ('phone-internet', 'EXPENSE', 'Phone and internet', 'Telecomunicações'),
           ('subscriptions', 'EXPENSE', 'Subscriptions', 'Subscrições'),
           ('fuel', 'EXPENSE', 'Fuel', 'Combustível'),
           ('tolls', 'EXPENSE', 'Tolls', 'Portagens'),
           ('insurance', 'EXPENSE', 'Insurance', 'Seguros'),
           ('pharmacy', 'EXPENSE', 'Pharmacy', 'Farmácia'),
           ('personal-care', 'EXPENSE', 'Personal care', 'Cuidados pessoais'),
           ('clothes', 'EXPENSE', 'Clothes', 'Roupa'),
           ('gym', 'EXPENSE', 'Gym', 'Ginásio'),
           ('education', 'EXPENSE', 'Education', 'Educação'),
           ('kids', 'EXPENSE', 'Kids', 'Filhos'),
           ('pets', 'EXPENSE', 'Pets', 'Animais'),
           ('travel', 'EXPENSE', 'Travel', 'Viagens'),
           ('gifts-given', 'EXPENSE', 'Gifts', 'Presentes'),
           ('taxes', 'EXPENSE', 'Taxes', 'Impostos'),
           ('freelance', 'INCOME', 'Freelance', 'Trabalhos extra'),
           ('bonus', 'INCOME', 'Bonus', 'Bónus'),
           ('interest', 'INCOME', 'Interest', 'Juros'),
           ('dividends', 'INCOME', 'Dividends', 'Dividendos'),
           ('holiday-allowance', 'INCOME', 'Holiday allowance', 'Subsídio de férias'),
           ('christmas-allowance', 'INCOME', 'Christmas allowance', 'Subsídio de Natal'),
           ('sales', 'INCOME', 'Sales', 'Vendas'),
           ('pension', 'INCOME', 'Pension', 'Reforma')
),
candidate AS (
    -- DISTINCT ON in case a name were on the list twice for one kind; no name is, today.
    SELECT DISTINCT ON (a.id)
           a.id, a.ledger_id, a.kind, a.archived_at, k.key, k.english,
           lower(a.name COLLATE pg_c_utf8) <> lower(k.english COLLATE pg_c_utf8) AS portuguese
    FROM accounts a
    JOIN known k
      ON k.kind = a.kind
     AND lower(a.name COLLATE pg_c_utf8)
         IN (lower(k.english COLLATE pg_c_utf8), lower(k.portuguese COLLATE pg_c_utf8))
    ORDER BY a.id, k.key
),
convertible AS (
    SELECT c.id, c.key, c.english
    FROM candidate c
    WHERE NOT c.portuguese
       OR c.archived_at IS NOT NULL
       OR NOT EXISTS (
           SELECT 1
           FROM accounts other
           WHERE other.ledger_id = c.ledger_id
             AND other.kind = c.kind
             AND other.id <> c.id
             AND other.archived_at IS NULL
             AND lower(other.name COLLATE pg_c_utf8) = lower(c.english COLLATE pg_c_utf8))
),
renamed AS (
    UPDATE accounts a
    SET name = c.english
    FROM convertible c
    WHERE a.id = c.id AND a.name <> c.english
)
INSERT INTO category_keys (category_id, key)
SELECT id, key FROM convertible;
