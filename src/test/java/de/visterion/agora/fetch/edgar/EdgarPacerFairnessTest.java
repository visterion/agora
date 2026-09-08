package de.visterion.agora.fetch.edgar;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pacer serialises every sec.gov request in the process, so the ORDER in which it hands the
 * turn out is a contract of its own. Round 3 slept inside an intrinsic monitor, which is not fair:
 * a market-wide {@code get_form4_transactions} sweep re-enters immediately after each 110 ms slice
 * and could barge past a {@code get_filing_text} caller that had been waiting since before it —
 * unbounded starvation, filed as the round's one open risk.
 *
 * <p><b>On what these tests can and cannot prove.</b> "The old implementation fails this" is not
 * something a test can GUARANTEE here: intrinsic-monitor unfairness is a JVM scheduling likelihood,
 * not a rule, so a barging test could in principle pass by luck against {@code synchronized}. It
 * was measured rather than assumed. A standalone harness ran this exact scenario against both
 * gates, 5 runs each, on the build JDK: with {@code synchronized} the latecomer was overtaken
 * <b>8, 9, 15, 9 and 13</b> times; with {@code ReentrantLock(true)} it was overtaken <b>1</b> time
 * in all 5. So {@link #aWaiterIsOvertakenAtMostOnceByAReEnteringSweep} does fail against the old
 * implementation — comfortably, but by a margin the scheduler owns rather than the contract. (The
 * harness had to be standalone because the test below detects "the latecomer is queued" through
 * {@code queuedCallers()}, which only means anything once the gate IS a lock.) To leave nothing
 * resting on a probability, {@link #theQueueIsFairByConstruction} pins the property directly: an
 * unfair gate cannot pass it at all, whatever the scheduler does.
 *
 * <p>No test here sleeps for real: the clock is frozen and the {@code Sleeper} is a seam, so a
 * "110 ms slice" costs nothing but is still a real critical section the other thread must wait on.
 * That same seam is where the latecomer's turn is observed: the pacer sleeps while HOLDING its
 * gate, so a {@code Sleeper} callback runs inside the critical section, and only a count read there
 * is a count read at the moment the turn was granted.
 */
class EdgarPacerFairnessTest {

    private static final LongSupplier FROZEN = () -> 0L;

    /**
     * The property the fix rests on, asserted where it cannot be lost by accident: a later
     * "simplification" back to {@code new ReentrantLock()} or {@code synchronized} fails here even
     * on a machine whose scheduler happens to be well behaved.
     */
    @Test void theQueueIsFairByConstruction() {
        assertThat(new EdgarRequestPacer(EdgarRequestPacer.MIN_SPACING_MS, FROZEN, ms -> { }).queueIsFair())
                .isTrue();
    }

    /**
     * The starvation scenario itself: a sweep looping through back-to-back slices, and one
     * latecomer that arrives mid-sweep. Under a fair queue the sweep's re-entry lands BEHIND the
     * latecomer, so the latecomer is overtaken at most once — by the slice already in flight when
     * it queued. Against the previous {@code synchronized} implementation the same scenario
     * measured 8-15 overtakes (see the class javadoc): a waiter repeatedly losing the race to the
     * thread that had just released — the "delayed past a 25 s consumer timeout" risk in
     * miniature, on a loop that costs no real sleep at all.
     */
    @Test void aWaiterIsOvertakenAtMostOnceByAReEnteringSweep() throws Exception {
        var sweepSlices = new AtomicInteger();
        var sweepIsInsideTheCriticalSection = new CountDownLatch(1);
        var latecomerHasQueued = new CountDownLatch(1);
        var sweepThread = new AtomicReference<Thread>();

        // Where the latecomer's turn is OBSERVED, and why it is observed here. The pacer sleeps
        // while holding its gate, so a Sleeper call is proof that its caller holds the lock right
        // now — and reading the sweep's counter from inside that critical section is the only way
        // to read it at the instant the turn was granted. Reading it after acquire() returns (the
        // first version of this test) reads it after the gate was already released, so the sweep
        // — whose slices cost nothing here: frozen clock, no-op sleeper, a bare lock/unlock per
        // iteration — can run hundreds of them while this thread is merely off-CPU. On an 8-core
        // dev box that never happened; on a 2-vCPU CI runner it did, reporting 375 and 1,000
        // overtakes for a queue that had in fact handed the turn over after exactly one slice.
        var servedAfterSlices = new AtomicInteger(-1);

        // The sweep's FIRST slice parks inside the pacer until the latecomer is provably queued;
        // every later slice returns at once, which is the "re-enters immediately" pattern.
        EdgarSearchService.Sleeper parkOnce = ms -> {
            if (Thread.currentThread() != sweepThread.get()) {   // the latecomer, holding the gate
                servedAfterSlices.compareAndSet(-1, sweepSlices.get());
                return;
            }
            if (sweepIsInsideTheCriticalSection.getCount() > 0) {
                sweepIsInsideTheCriticalSection.countDown();
                latecomerHasQueued.await(5, TimeUnit.SECONDS);
            }
        };
        var pacer = new EdgarRequestPacer(EdgarRequestPacer.MIN_SPACING_MS, FROZEN, parkOnce);

        var stop = new AtomicInteger();
        var sweep = new Thread(() -> {
            try {
                while (stop.get() == 0 && sweepSlices.get() < 1_000) {
                    pacer.acquire();
                    sweepSlices.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sweep");
        sweepThread.set(sweep);
        sweep.start();

        assertThat(sweepIsInsideTheCriticalSection.await(5, TimeUnit.SECONDS)).isTrue();
        int slicesBeforeTheLatecomer = sweepSlices.get();

        var latecomer = new Thread(() -> {
            try {
                pacer.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "latecomer");
        latecomer.start();
        while (pacer.queuedCallers() == 0) {
            Thread.onSpinWait();
        }
        latecomerHasQueued.countDown();   // the sweep is now free to run flat out

        latecomer.join(10_000);
        stop.set(1);
        sweep.join(10_000);

        assertThat(servedAfterSlices.get())
                .as("slices the sweep completed before the latecomer got its turn")
                .isBetween(slicesBeforeTheLatecomer, slicesBeforeTheLatecomer + 1);
    }

    /**
     * The bounded-wait decision, pinned as behaviour: the pacer refuses nobody. A caller that
     * arrives behind a long sweep waits and is then SERVED — it does not get a "busy" error the
     * way {@code get_filing_text}'s memory bound hands out {@code filing_fetch_busy}. That is
     * deliberate (see the class javadoc): a fair queue makes the wait ~1 s at realistic
     * concurrency, and a new refusal would be one more degradation that can reach a consumer
     * looking like an empty result.
     */
    @Test void aQueuedCallerIsServedRatherThanRefusedAsBusy() throws Exception {
        var pacer = new EdgarRequestPacer(EdgarRequestPacer.MIN_SPACING_MS, FROZEN, ms -> { });
        int callers = 16;
        var everyoneIsReady = new CountDownLatch(callers);
        var served = new AtomicInteger();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        var threads = new Thread[callers];
        for (int i = 0; i < callers; i++) {
            threads[i] = new Thread(() -> {
                everyoneIsReady.countDown();
                try {
                    everyoneIsReady.await(5, TimeUnit.SECONDS);
                    pacer.acquire();
                    served.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(10_000);
        }

        assertThat(failures).isEmpty();
        assertThat(served).hasValue(callers);
    }
}
