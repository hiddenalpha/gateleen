package org.swisspush.gateleen.core.util;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.nanoTime;

public class SlicedLoop<T> {
    private static final Logger log = LoggerFactory.getLogger(SlicedLoop.class);
    private static final String DEBUG_HINT_DEFAULT = "Follow the stack to see who created the EventLoop-hog";
    private static final long yellingCoolDownMs = 60_000;
    private static final AtomicInteger numEnqueuedTasks = new AtomicInteger(0);
    private static volatile long lastYellingEpochMs = 0;
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
    private int numSlices = 0;

    public SlicedLoop(Vertx vertx, Iterator<T> src, Destination<T> dst) {
        this(vertx, DEBUG_HINT_DEFAULT, src, dst);
    }

    public SlicedLoop(Vertx vertx, String debugHint, Iterator<T> src, Destination<T> dst) {
        this(vertx, 4_000_000, debugHint, src, dst);
    }

    public SlicedLoop(Vertx vertx, int sliceThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
        this(vertx, sliceThresholdNs, 16_000_000, 16_000_000, debugHint, src, dst);
    }

    private SlicedLoop(Vertx vertx, int sliceThresholdNs, int postponeDelayNs, int yellingThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
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

    public void pause() {
        pauseRequest = true;
    }

    public void resume() {
        if (isRunning) throw new IllegalStateException("Already running");
        isRunning = true;
        enqueueNextSlice();
    }

    /**
     * Returns how many tasks currently are waiting to get some CPU time. Can be
     * an interesting value for metrics for example.
     */
    public int getEnqueuedTasksCount() {
        return numEnqueuedTasks.get();
    }

    private void enqueueNextSlice() {
        long delayMs = (postponeDelayNs + 500_000) / 1_000_000;
        if (delayMs < 1) delayMs = 1; // <- Smallest value vertx allows.
        int taskNum = numEnqueuedTasks.incrementAndGet();
        if (taskNum > 2) {
            // Progressively slow-down enqueuing on high load.
            delayMs = Math.min(delayMs * taskNum, yellingThresholdNs/500);
        }
        if (taskNum >= 128) {
            log.debug("Schedule {} async task with delay {}.", taskNum, delayMs);
        }else if (taskNum >= 32) {
            log.trace("Schedule {} async task with delay {}.", taskNum, delayMs);
        }
        vertx.setTimer(delayMs, tmrId -> {
            numEnqueuedTasks.decrementAndGet();
            iterateNextSlice();
        });
    }

    private void iterateNextSlice() {
        long sliceStartNs = nanoTime();
        long iChild = 0;
        numSlices += 1;
        for (;; ++iChild) {
            if (pauseRequest) {
                isRunning = false; // <- Reset barrier so client is able to resume.
                return; // Abort current iteration.
            }
            if (source.hasNext()) {
                numElems += 1;
                dst.onNext(source.next());
                long nowNs = nanoTime();
                long usedCpuNs = nowNs - sliceStartNs;
                if (usedCpuNs > Long.MAX_VALUE / 2) {
                    // nanoTime did overflow since we measured start point. Unlikely, but
                    // still can happen. See JavaDoc of nanoTime(). Applying yet another
                    // overflow on the difference by unsigned-max-value reverts the effect
                    // and we end up with our expected difference.
                    usedCpuNs += Long.MAX_VALUE;
                }
                if (usedCpuNs > sliceThresholdNs) {
                    if (usedCpuNs > yellingThresholdNs) {
                        // ONE single task alone managed to exceed the complete slice limit.
                        // This is everything else than "good". So we'll yell loud about it.
                        // But we'll log only a few of them on WARN level to not kill performance
                        // due to logging.
                        long nowEpochMs = System.currentTimeMillis();
                        if (lastYellingEpochMs + yellingCoolDownMs < nowEpochMs) {
                            lastYellingEpochMs = nowEpochMs;
                            log.warn("Task i={} blocked event-loop for {} ms.", iChild, usedCpuNs/1_000_000f, this.stackOfCreator);
                        } else {
                            log.trace("Task i={} blocked event-loop for {} ms.", iChild, usedCpuNs/1_000_000f, this.stackOfCreator);
                        }
                    } else {
                        log.trace("Slice-quota of {} ns exceeded ({} turns consumed {} ns). Give up CPU and continue later.", iChild, sliceThresholdNs, usedCpuNs);
                    }
                    break; // goto endLoop and schedule the remaining elements for later.
                }else{
                    continue; // Process next element right now.
                }
            }
            log.debug("Broke down iteration of {} elements into {} tasks.", numElems, numSlices);
            dst.onEnd();
            return;
        }
        // Slice-quota exceeded. Give up CPU and continue later.
        enqueueNextSlice();
    }

    public static interface Destination<T> {
        void onNext(T e);
        void onEnd();
    }

    /**
     * Does not get thrown. Only used to log stack-traces of code hogging up
     * the event-loop for too much time.
     */
    public static class EventLoopHogException extends RuntimeException {
        private EventLoopHogException(String message) {
            super(message);
        }
    }
}
