package io.github.muzachio.moneytrail.personal.internal;

/** What a category is called: a name the app knows and translates, or one the person typed. */
sealed interface Naming {

    /**
     * The name kept in the account, which the rule against repeated names compares: a known name's
     * English one, so that it is taken in every language at once.
     */
    String stored();

    record Known(KnownName name) implements Naming {
        @Override
        public String stored() {
            return name.english();
        }
    }

    record Typed(String text) implements Naming {

        // The schema refuses blank names, but its pattern only knows ASCII spaces; strip() knows all.
        public Typed {
            text = text.strip();
            if (text.isEmpty()) {
                throw CategoryException.badRequest("A category needs a name.");
            }
        }

        @Override
        public String stored() {
            return text;
        }
    }
}
