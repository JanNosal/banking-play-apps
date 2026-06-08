package dev.jannosal.bank.product.model;

/**
 * Product Directory references shared by the seeder, the migration worker and the tests so they never
 * drift. These are the catalogue ids a {@link DirectoryEntry} points at via
 * {@link DirectoryEntry#productDirectoryReference()} — modelled on the BIAN <i>Product Directory</i>
 * service domain. All carry the {@code PD-} prefix.
 *
 * <p>The migration scans by {@link #PREMIER_BANKING}: every customer in the Premier Banking proposition
 * holds one membership entry with that reference, which is how discovery finds them (it replaces the
 * old TMF "bundle parent"). The Mortgage family ({@link #MORTGAGE_LOAN}, {@link #HOME_INSURANCE}) is a
 * second, unrelated set used to prove the scan is selective: a customer who holds only those is never
 * migrated unless they also hold a Premier Banking membership.
 */
public final class ProductDirectory {

    private ProductDirectory() {}

    /** Premier Banking membership — the discovery filter. A customer holding this is migrated in full. */
    public static final String PREMIER_BANKING = "PD-PREMIER-BANKING";

    /** Product-directory references for the Premier Banking proposition. */
    public static final String CURRENT_ACCOUNT = "PD-CURRENT-ACCOUNT";
    public static final String SAVINGS_ACCOUNT = "PD-SAVINGS-ACCOUNT";
    public static final String OVERDRAFT = "PD-OVERDRAFT";
    public static final String DEBIT_CARD = "PD-DEBIT-CARD";

    /** A second, unrelated set — the Mortgage proposition (NOT migrated unless the customer also holds PREMIER_BANKING). */
    public static final String MORTGAGE_LOAN = "PD-MORTGAGE-LOAN";
    public static final String HOME_INSURANCE = "PD-HOME-INSURANCE";
}
