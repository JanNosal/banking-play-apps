package dev.jannosal.bank.mockedapps.seed;

import dev.jannosal.bank.mockedapps.persistence.DirectoryEntryDocument;
import dev.jannosal.bank.product.model.DirectoryEntry;
import dev.jannosal.bank.product.model.Involvedparty;
import dev.jannosal.bank.product.model.Product;
import dev.jannosal.bank.product.model.ProductAgreement;
import dev.jannosal.bank.product.model.ProductDirectory;
import dev.jannosal.bank.product.model.ProductFeature;
import dev.jannosal.bank.product.model.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a realistic legacy Customer Product and Service Directory: many customers with varied
 * holdings (most own a single Premier proposition, a few are "whales" with many), three profiles
 * (Premier only, Mortgage only, and both). Each Premier customer holds a Premier current account whose
 * {@code productDirectoryReference} is {@link ProductDirectory#PREMIER_BANKING} — the marker discovery
 * scans for — plus a debit card, sometimes an overdraft, and one or two savings accounts. Mortgage
 * customers hold a mortgage loan and home insurance and, lacking the Premier marker, are never migrated
 * unless they are also Premier. Generation is driven by a fixed RNG seed so the dataset — and its counts
 * — are reproducible, which the end-to-end test relies on.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final MongoTemplate mongo;
    private final SeedProperties props;

    // Monotonic instance-id counters (single-threaded generation).
    private long entrySeq = 0;
    private long resSeq = 0;
    private long svcSeq = 0;

    public DataSeeder(MongoTemplate mongo, SeedProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            log.info("Seeding disabled (seed.enabled=false); existing data count={}",
                    mongo.estimatedCount(DirectoryEntryDocument.class));
            return;
        }
        seed();
    }

    /** Runs the generator with the configured properties; safe to call directly from tests. */
    public SeedStats seed() {
        long start = System.currentTimeMillis();
        if (props.reset()) {
            mongo.dropCollection(DirectoryEntryDocument.class);
        }

        Random rng = new Random(props.randomSeed());
        List<DirectoryEntryDocument> batch = new ArrayList<>(props.batchSize());
        long totalEntries = 0;
        int premierCustomers = 0;
        int mortgageCustomers = 0;
        int bothCustomers = 0;

        for (int c = 0; c < props.customers(); c++) {
            String customerId = String.format("CUST-%06d", c);
            String customerName = companyName(rng);

            double profile = rng.nextDouble();
            boolean hasPremier = profile < 0.70 || profile >= 0.85; // Premier-only or both
            boolean hasMortgage = profile >= 0.70;                  // Mortgage-only or both
            if (hasPremier && hasMortgage) {
                bothCustomers++;
            } else if (hasPremier) {
                premierCustomers++;
            } else {
                mortgageCustomers++;
            }

            List<DirectoryEntry> entries = new ArrayList<>();
            if (hasPremier) {
                int holdings = drawHoldingCount(rng);
                for (int p = 0; p < holdings; p++) {
                    entries.addAll(buildPremierHoldings(rng, customerId, customerName));
                }
            }
            if (hasMortgage) {
                int holdings = Math.max(1, drawHoldingCount(rng) / 2);
                for (int p = 0; p < holdings; p++) {
                    entries.addAll(buildMortgageHoldings(rng, customerId, customerName));
                }
            }

            for (DirectoryEntry entry : entries) {
                batch.add(new DirectoryEntryDocument(entry));
                totalEntries++;
                if (batch.size() >= props.batchSize()) {
                    mongo.insertAll(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            mongo.insertAll(batch);
        }

        SeedStats stats = new SeedStats(props.customers(), premierCustomers, mortgageCustomers, bothCustomers, totalEntries);
        log.info("Seeded {} directory entries for {} customers in {} ms ({})",
                totalEntries, props.customers(), System.currentTimeMillis() - start, stats);
        return stats;
    }

    // ---- distribution -------------------------------------------------------

    /** Holdings-per-customer: long tail — most own one set, a few are whales. */
    private int drawHoldingCount(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.65) return 1;
        if (r < 0.85) return 2;
        if (r < 0.93) return 3;
        if (r < 0.97) return 4 + rng.nextInt(3);   // 4..6
        if (r < 0.99) return 7 + rng.nextInt(6);   // 7..12
        return 13 + rng.nextInt(18);               // 13..30 (whales)
    }

    // ---- Premier Banking proposition ---------------------------------------

    private List<DirectoryEntry> buildPremierHoldings(Random rng, String customerId, String customerName) {
        List<DirectoryEntry> out = new ArrayList<>();
        Involvedparty customer = customer(customerId, customerName);

        // Premier current account — carries the PREMIER_BANKING marker discovery scans for.
        String accountNumber = String.format("%010d", 1_000_000_000L + (long) (rng.nextDouble() * 8_999_999_999L));
        out.add(entry(ProductDirectory.PREMIER_BANKING, "Premier Current Account", "CurrentAccountProduct", customer)
                .features(List.of(
                        feature("AccountNumber", "accountNumber", accountNumber, null),
                        feature("Currency", "currency", "USD", null),
                        feature("Tier", "tier", "Premier", null),
                        resourceFeature("LedgerAccount", "ledgerAccount"),
                        resourceFeature("Iban", "iban")))
                .service(List.of(service("Payments", "FinancialService")))
                .agreement(agreement("CurrentAccountAgreement", "monthly", "25.00", "USD"))
                .build());

        // Debit card.
        String pan = String.format("%016d", (long) (rng.nextDouble() * 1_000_000_000_000_000L));
        out.add(entry(ProductDirectory.DEBIT_CARD, "Debit Card", "DebitCardProduct", customer)
                .serial("CARD-" + pan.substring(0, 6) + "******" + pan.substring(12))
                .features(List.of(
                        feature("CardNetwork", "cardNetwork", rng.nextBoolean() ? "VISA" : "MASTERCARD", null),
                        feature("Contactless", "contactless", "true", null),
                        resourceFeature("Card", "card")))
                .agreement(agreement("CurrentAccountAgreement", null, null, null))
                .build());

        // Overdraft facility (~50% of customers).
        if (rng.nextDouble() < 0.5) {
            out.add(entry(ProductDirectory.OVERDRAFT, "Overdraft Facility", "OverdraftProduct", customer)
                    .features(List.of(
                            feature("LimitMinor", "limitMinor", String.valueOf((1 + rng.nextInt(20)) * 50000), null),
                            feature("AprPct", "aprPct", String.valueOf(15 + rng.nextInt(25)), null)))
                    .agreement(agreement("ConsumerLoanAgreement", null, null, null))
                    .build());
        }

        // One or two savings accounts.
        int savings = 1 + rng.nextInt(2);
        for (int s = 0; s < savings; s++) {
            out.add(entry(ProductDirectory.SAVINGS_ACCOUNT, "Savings Account", "SavingAccountProduct", customer)
                    .features(List.of(
                            feature("InterestRatePct", "interestRatePct", String.valueOf(1 + rng.nextInt(4)), null),
                            feature("Currency", "currency", "USD", null),
                            resourceFeature("LedgerAccount", "ledgerAccount")))
                    .service(List.of(service("Statements", "InformationService")))
                    .agreement(agreement("SavingsAccountAgreement", null, null, null))
                    .build());
        }
        return out;
    }

    // ---- Mortgage proposition ----------------------------------------------

    private List<DirectoryEntry> buildMortgageHoldings(Random rng, String customerId, String customerName) {
        List<DirectoryEntry> out = new ArrayList<>();
        Involvedparty customer = customer(customerId, customerName);

        out.add(entry(ProductDirectory.MORTGAGE_LOAN, "Mortgage Loan", "MortgageLoanProduct", customer)
                .serial("LN-" + (100000000L + (long) (rng.nextDouble() * 899999999L)))
                .features(List.of(
                        feature("PrincipalMinor", "principalMinor", String.valueOf((50 + rng.nextInt(450)) * 100000), null),
                        feature("TermMonths", "termMonths", String.valueOf(120 + rng.nextInt(241)), null),
                        resourceFeature("LedgerAccount", "ledgerAccount")))
                .agreement(agreement("MortgageLoanAgreement", null, null, null))
                .build());

        out.add(entry(ProductDirectory.HOME_INSURANCE, "Home Insurance", "InsuranceProduct", customer)
                .service(List.of(service("Claims", "BusinessService")))
                .agreement(agreement("CurrentAccountAgreement", "monthly", "18.00", "USD"))
                .build());
        return out;
    }

    // ---- builders -----------------------------------------------------------

    private EntryBuilder entry(String productDirectoryReference, String productName, String productType, Involvedparty customer) {
        return new EntryBuilder(productDirectoryReference, productName, productType, customer);
    }

    private ProductFeature feature(String type, String name, String value, String identification) {
        return new ProductFeature(type, name, value, identification);
    }

    private ProductFeature resourceFeature(String type, String name) {
        String id = String.format("RES-%s-%07d", type.toUpperCase(), resSeq++);
        return new ProductFeature(type, name, null, id);
    }

    private Service service(String name, String serviceType) {
        String id = String.format("SVC-%s-%07d", name.toUpperCase(), svcSeq++);
        return new Service(id, name + " Service", serviceType, "Active");
    }

    private ProductAgreement agreement(String agreementType, String feePeriod, String feeAmount, String currency) {
        ProductAgreement.ProductFee fee = feeAmount == null ? null
                : new ProductAgreement.ProductFee("AccountServicingFee", feePeriod,
                new ProductAgreement.Money(feeAmount, currency));
        return new ProductAgreement(agreementType, "Active", "2026-01-15", fee,
                new ProductAgreement.AgreementValidityPeriod("2026-01-15T00:00:00Z", null));
    }

    private Involvedparty customer(String customerId, String name) {
        return new Involvedparty(customerId, name, "Customer", "INV-" + customerId);
    }

    private String href(String id) {
        return "http://legacy/customer-product-and-service-directory/" + id;
    }

    private String companyName(Random rng) {
        String[] a = {"Maple", "Cedar", "Harbor", "Summit", "Riverside", "Oakwood", "Pioneer", "Lakeside",
                "Granite", "Beacon", "Ironwood", "Crescent", "Meadow", "Northstar", "Silver"};
        String[] b = {"Dental", "Logistics", "Bakery", "Auto", "Clinic", "Realty", "Diner", "Hardware",
                "Law Group", "Studios", "Plumbing", "Fitness", "Veterinary", "Pharmacy", "Outfitters"};
        return a[rng.nextInt(a.length)] + " " + b[rng.nextInt(b.length)] + ", LLC";
    }

    /** Mutable builder that emits an immutable {@link DirectoryEntry}; keeps the generator readable. */
    private final class EntryBuilder {
        private final String productDirectoryReference;
        private final String productName;
        private final String productType;
        private final Involvedparty customer;
        private String serial;
        private List<ProductFeature> features;
        private List<Service> services;
        private ProductAgreement agreement;

        EntryBuilder(String productDirectoryReference, String productName, String productType, Involvedparty customer) {
            this.productDirectoryReference = productDirectoryReference;
            this.productName = productName;
            this.productType = productType;
            this.customer = customer;
        }

        EntryBuilder serial(String s) { this.serial = s; return this; }
        EntryBuilder features(List<ProductFeature> f) { this.features = f; return this; }
        EntryBuilder service(List<Service> s) { this.services = s; return this; }
        EntryBuilder agreement(ProductAgreement a) { this.agreement = a; return this; }

        DirectoryEntry build() {
            String id = String.format("CPSD-%07d", entrySeq++);
            String productInstanceRef = "PRD-" + id;
            Product product = new Product(productInstanceRef, productName,
                    productName + " for " + customer.partyName(), productType, "Active", serial, features);
            Involvedparty servicer = new Involvedparty("BANK-RETAIL", "Retail Bank Servicing", "Servicer", null);
            return new DirectoryEntry(
                    id, href(id), productName + " held by " + customer.partyName(), "Active",
                    "2026-01-15T00:00:00Z", customer, servicer, productDirectoryReference,
                    product, services, agreement);
        }
    }

    public record SeedStats(int customers, int premierOnlyCustomers, int mortgageOnlyCustomers, int bothCustomers,
                            long totalEntries) {
        /** Customers whose directory the migration must pick up (hold at least one Premier Banking entry). */
        public int migratedCustomers() {
            return premierOnlyCustomers + bothCustomers;
        }
    }
}
