package dev.jannosal.bank.migration.activity;

import dev.jannosal.bank.migration.api.CustomerLoadResult;
import dev.jannosal.bank.migration.client.LegacyInventoryClient;
import dev.jannosal.bank.migration.client.NewInventoryClient;
import dev.jannosal.bank.product.model.DirectoryEntry;
import io.temporal.activity.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Loads one customer's full directory. Page fetches and upserts run on virtual threads so a customer
 * with many entries (a "whale") still completes quickly. The method is idempotent — the new core
 * upserts by id — so Temporal can retry the whole activity safely.
 */
public class LoaderActivitiesImpl implements LoaderActivities {

    private final LegacyInventoryClient legacy;
    private final NewInventoryClient newInventory;

    public LoaderActivitiesImpl(LegacyInventoryClient legacy, NewInventoryClient newInventory) {
        this.legacy = legacy;
        this.newInventory = newInventory;
    }

    @Override
    public CustomerLoadResult loadCustomer(String customerId, int pageSize) {
        // Page 0 first to learn the total, then fetch the remaining pages in parallel.
        LegacyInventoryClient.Page first = legacy.queryByCustomer(customerId, 0, pageSize);
        long total = first.total();
        int pages = pageSize <= 0 ? 1 : (int) Math.ceil((double) total / pageSize);

        List<DirectoryEntry> all = new ArrayList<>(first.entries());
        if (pages > 1) {
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<List<DirectoryEntry>>> futures = new ArrayList<>();
                for (int p = 1; p < pages; p++) {
                    int offset = p * pageSize;
                    futures.add(exec.submit(() -> legacy.queryByCustomer(customerId, offset, pageSize).entries()));
                }
                for (Future<List<DirectoryEntry>> f : futures) {
                    all.addAll(f.get());
                }
            } catch (ExecutionException e) {
                throw Activity.wrap(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Activity.wrap(e);
            }
        }

        // Heartbeat so a large directory does not look stuck to the Temporal server.
        Activity.getExecutionContext().heartbeat(all.size());

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (DirectoryEntry entry : all) {
                futures.add(exec.submit(() -> newInventory.upsert(entry)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (ExecutionException e) {
            throw Activity.wrap(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Activity.wrap(e);
        }

        return new CustomerLoadResult(customerId, all.size(), Math.max(pages, 1));
    }
}
