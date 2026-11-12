package dev.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.minibank.auth.Caller;
import dev.minibank.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/** The section 12 k6 contention scenarios, in miniature, against real Postgres row locks. */
class LedgerConcurrencyTests {

    private final LedgerHarness h = LedgerHarness.get();

    private final Caller service = Caller.service("svc-" + UUID.randomUUID());

    @Test
    void oppositeTransfersBetweenTheSameTwoAccountsNeverDeadlock() throws Exception {
        UUID a = h.newAccount(h.newCustomer());
        UUID b = h.newAccount(h.newCustomer());
        deposit(a, 1_000_000);
        deposit(b, 1_000_000);

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            boolean forward = i % 2 == 0;
            work.add(() -> {
                long amount = ThreadLocalRandom.current().nextLong(1, 100);
                h.ledger.transfer(
                        service,
                        UUID.randomUUID().toString(),
                        new LedgerService.TransferCommand(forward ? a : b, forward ? b : a, amount, "USD", null));
                return null;
            });
        }
        List<Throwable> failures = runConcurrently(16, work);

        assertThat(failures).as("deadlocks or other failures").isEmpty();
        assertThat(h.balance(a) + h.balance(b)).isEqualTo(2_000_000);
        LedgerInvariantPropertyTests.assertGlobalInvariants(h);
    }

    @Test
    void overdraftRaceLetsExactlyTheAffordableTransfersThrough() throws Exception {
        UUID customer = h.newCustomer();
        UUID source = h.newAccount(customer);
        UUID destination = h.newAccount(h.newCustomer());
        deposit(source, 1_000);
        Caller owner = Caller.customer("cust-" + UUID.randomUUID(), customer);

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            work.add(() -> {
                h.ledger.transfer(
                        owner,
                        UUID.randomUUID().toString(),
                        new LedgerService.TransferCommand(source, destination, 100, "USD", null));
                return null;
            });
        }
        List<Throwable> failures = runConcurrently(20, work);

        assertThat(failures)
                .hasSize(90)
                .allSatisfy(f -> assertThat(f)
                        .isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).slug())
                        .isEqualTo("insufficient-funds"));
        assertThat(h.balance(source)).isZero();
        assertThat(h.balance(destination)).isEqualTo(1_000);
        LedgerInvariantPropertyTests.assertGlobalInvariants(h);
    }

    @Test
    void sameKeyReplayedInParallelPostsExactlyOnce() throws Exception {
        UUID account = h.newAccount(h.newCustomer());
        String key = UUID.randomUUID().toString();
        var command = new LedgerService.DepositCommand(account, 2_500, "USD", "parallel");

        List<Future<LedgerService.Result>> results = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 50; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return h.ledger.deposit(service, key, command);
                }));
            }
            start.countDown();
        }
        List<LedgerService.Result> done = new ArrayList<>();
        for (var f : results) {
            done.add(f.get());
        }

        assertThat(done).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(done)
                .extracting(r -> r.transaction().id())
                .containsOnly(done.getFirst().transaction().id());
        assertThat(h.balance(account)).isEqualTo(2_500);
        LedgerInvariantPropertyTests.assertGlobalInvariants(h);
    }

    private void deposit(UUID account, long amount) {
        h.ledger.deposit(
                service, UUID.randomUUID().toString(), new LedgerService.DepositCommand(account, amount, "USD", null));
    }

    /** Starts every task at once on {@code threads} threads and returns the failures. */
    private static List<Throwable> runConcurrently(int threads, List<Callable<Void>> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (Callable<Void> task : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
        }
        List<Throwable> failures = new ArrayList<>();
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException ex) {
                failures.add(ex.getCause());
            }
        }
        return failures;
    }
}
