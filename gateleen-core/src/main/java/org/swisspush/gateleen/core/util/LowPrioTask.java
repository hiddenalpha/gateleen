package org.swisspush.gateleen.core.util;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.nanoTime;

/**
 * An asynchronous loop which splits the CPU usage to multiple reactor tasks
 * based on a time limit.
 *
 * TODO
 *
 * @param <T>
 *     Type of the elements to iterate.
 */
public class LowPrioTask<T> {
    private static final Logger log = LoggerFactory.getLogger(LowPrioTask.class);
    private static final String DEBUG_HINT_DEFAULT = "Follow the stack to see who created the EventLoop-hog";
    private static final long yellingCoolDownMs = 600_000;
    private static final AtomicInteger numEnqueuedTasks = new AtomicInteger(0);
    private static long lastYellingEpochMs = 0;
    private static final Queue<Runnable> prioBelowNormal = new ArrayDeque<>();
    private final Vertx vertx;
    private final Iterator<T> source;
    private final Destination<T> dst;
    private final int sliceThresholdNs;
    private final int yellingThresholdNs;
    private final int postponeDelayNs;
    private final RuntimeException stackOfCreator;
    private boolean isRunning = false;
    private boolean pauseRequest = false;
    private long numElems = 0;
    private int numTasks = 0;

    /**
     * @param src
     *      The input to iterate.
     * @param dst
     *      Performs the work to be done inside the loop.
     */
    public LowPrioTask(Vertx vertx, Iterator<T> src, Destination<T> dst) {
        this(vertx, DEBUG_HINT_DEFAULT, src, dst);
    }

    /**
     * @param debugHint
     *      Makes bug-hunting easier as this string gets used in case a EventLoop
     *      hog gets reported.
     * @param src
     *      The input to iterate.
     * @param dst
     *      Performs the work to be done inside the loop.
     */
    public LowPrioTask(Vertx vertx, String debugHint, Iterator<T> src, Destination<T> dst) {
        this(vertx, 4_000_000, debugHint, src, dst);
    }

    /**
     * @param sliceThresholdNs
     *      Duration in nanoseconds after which the iteration gets paused and resumed later.
     * @param debugHint
     *      Makes bug-hunting easier as this string gets used in case a EventLoop
     *      hog gets reported.
     * @param src
     *      The input to iterate.
     * @param dst
     *      Performs the work to be done inside the loop.
     */
    public LowPrioTask(Vertx vertx, int sliceThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
        this(vertx, sliceThresholdNs, 16_000_000, 16_000_000, debugHint, src, dst);
    }

    /**
     * @param sliceThresholdNs
     *      Duration in nanoseconds after which the iteration gets paused and resumed later.
     * @param postponeDelayNs
     *      Duration in nanoseconds to stay in 'pause' before continuing. HINT:
     *      The used vertx API only allows a min value of 1'000'000 nanoseconds
     *      (aka 1ms). This param only is in nanoseconds to keep all params in
     *      the same unit to reduce possible confusion.
     * @param yellingThresholdNs
     *      If a task (alias: slice) exceeds this duration limit in nanoseconds,
     *      this will be logged.
     * @param debugHint
     *      Makes bug-hunting easier as this string gets used in case a EventLoop
     *      hog gets reported.
     * @param src
     *      The input to iterate.
     * @param dst
     *      Performs the work to be done inside the loop.
     */
    private LowPrioTask(Vertx vertx, int sliceThresholdNs, int postponeDelayNs, int yellingThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
        this.vertx = vertx;
        this.source = src;
        this.dst = dst;
        this.sliceThresholdNs = sliceThresholdNs;
        this.yellingThresholdNs = yellingThresholdNs;
        this.postponeDelayNs = postponeDelayNs;
        // Need to take stack trace early. Because in the place we need it, the
        // problematic code is no longer in our stack.
        this.stackOfCreator = new EventLoopHogException(debugHint == null ? DEBUG_HINT_DEFAULT : debugHint);
    }

    /**
     * Explicitly requests to pause the iteration. Think for {@link HttpClientRequest#pause()}.
     */
    public void pause() {
        pauseRequest = true;
    }

    /**
     * Resume (or start) the paused iteration. Think for {@link HttpClientRequest#resume()}.
     */
    public void resume() {
        if (isRunning){
            throw new IllegalStateException("Already running");
        }
        pauseRequest = false;
        isRunning = true;
        enqueueNextSlice();
    }

    /**
     * Returns how many tasks currently are waiting to get some CPU time. Can be
     * an interesting value for metrics for example.
     */
    public static int getEnqueuedTasksCount() {
        return numEnqueuedTasks.get() + prioBelowNormal.size();
    }

    private void enqueueNextSlice() {
        final int maxTasks = 2; // TODO move to field.
        prioBelowNormal.add(this::iterateNextSlice);
        if (numEnqueuedTasks.get() > maxTasks) {
            log.trace("Don't flood reactors event queue. Task will be enqueued later.");
            return;
        }
        Runnable nextTask = prioBelowNormal.poll();
        if (nextTask == null) { // Should not happen as we added an element above. But who knows.
            log.trace("No more tasks to enqueue.");
            return;
        }
        log.trace("Enqueue another task from our low-prio line.");
        numEnqueuedTasks.incrementAndGet();
        vertx.setTimer(1, tmrId -> {
            numEnqueuedTasks.decrementAndGet();
            enqueueNextSlice();
            nextTask.run();
        });
    }

    private void iterateNextSlice() {
        long sliceStartNs = nanoTime();
        long iChild = 0;
        numTasks += 1;
        for (;; ++iChild) {
            if (pauseRequest) {
                pauseRequest = false;
                isRunning = false; // <- Reset barrier so client is able to resume.
                return; // Abort current iteration.
            }
            if ( ! source.hasNext()) {
                // End of iteration reached. No more elements to process. We're done.
                log.debug("Broke down iteration of {} elements into {} tasks.", numElems, numTasks);
                dst.onEnd();
                return;
            }
            // Process next element
            numElems += 1;
            dst.onNext(source.next());
            long nowNs = nanoTime();
            long usedCpuNs = nowNs - sliceStartNs;
            if (usedCpuNs > Long.MAX_VALUE / 2) {
                // nanoTime did overflow since we measured start point. Unlikely, but
                // still can happen. See JavaDoc of nanoTime(). Applying yet another
                // overflow on the difference by signed-max-value reverts the effect
                // and we end up with our expected difference.
                usedCpuNs += Long.MAX_VALUE;
            }
            if (usedCpuNs > sliceThresholdNs) {
                if (usedCpuNs > yellingThresholdNs) {
                    // This case does not match my definition of a "good" task. So we'll yell
                    // loud about it. But we'll log only a few of them on WARN level to not
                    // kill performance due to logging.
                    long nowEpochMs = System.currentTimeMillis();
                    if (lastYellingEpochMs + yellingCoolDownMs < nowEpochMs) {
                        lastYellingEpochMs = nowEpochMs;
                        log.warn("Task i={} blocked event-loop for {} ms.", iChild, usedCpuNs/1_000_000f, this.stackOfCreator);
                    } else {
                        log.trace("Task i={} blocked event-loop for {} ms.", iChild, usedCpuNs/1_000_000f, this.stackOfCreator);
                    }
                } else {
                    log.trace("Slice-quota of {} ns exceeded ({} turns consumed {} ns). Give up CPU and continue later.", sliceThresholdNs, iChild + 1, usedCpuNs);
                }
                // Slice-quota exceeded. Give up CPU and continue later.
                return;
            }
        }
    }

    public static interface Destination<T> {
        void onNext(T e);
        void onEnd();
    }

    /**
     * Does not get thrown. Only used to log stack-traces of code which hog
     * the event-loop for too long.
     */
    public static class EventLoopHogException extends RuntimeException {
        private EventLoopHogException(String message) {
            super(message);
        }
    }
}
