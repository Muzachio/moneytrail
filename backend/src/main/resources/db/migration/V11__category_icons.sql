-- The icon a person picked for a category of their own (#67). The table belongs to `personal`, so
-- the ledger's accounts know nothing about how the app draws a category. A default category has no
-- row: its icon comes from its key, the same for everyone, and never changes. A category of the
-- person's own without a row, created before this migration, shows the plain tag.
--
-- The check is the API's CategoryIcon list, so the database never holds an icon the app cannot
-- draw. A new icon needs a migration that replaces the check.
CREATE TABLE category_icons (
    category_id uuid PRIMARY KEY REFERENCES accounts (id),
    icon        text NOT NULL CONSTRAINT category_icons_icon_check CHECK (icon IN (
        'house', 'utensils', 'tram-front', 'receipt', 'shopping-bag', 'ticket', 'heart', 'tag',
        'banknote', 'rotate-ccw', 'gift', 'car', 'fuel', 'plane', 'coffee', 'shirt', 'smartphone',
        'graduation-cap', 'baby', 'paw-print', 'dumbbell', 'wrench', 'piggy-bank', 'briefcase'))
);
