package org.swisspush.gateleen.core.util;

import io.vertx.core.Vertx;

import java.util.Iterator;

public class SlicedLoop<T> implements Runnable {
    private final Vertx vertx;
    private final Iterator<T> source;
    private final int sliceSize;
    private final Destination<T> dst;
    private boolean gotFired = false;
    private boolean breakRequest = false;

    public SlicedLoop(Vertx vertx, Iterator<T> src, Destination<T> dst) {
        this.vertx = vertx;
        this.source = src;
        this.dst = dst;
        this.sliceSize = 100;
    }

    @Override
    public void run() {
        if (gotFired) throw new IllegalStateException("MUST NOT fire more than once");
        gotFired = true;
        iterateNextSlice();
    }

    public void doBreak(){
        breakRequest = true;
    }

    private void iterateNextSlice() {
        for (int remaining = sliceSize; remaining > 0; --remaining) {
            if (breakRequest) {
                return; // Client did 'break'. So simply abort iteration.
            }
            if (source.hasNext()) {
                dst.onNext(source.next());
                continue;
            }
            dst.onEnd();
            return;
        }
        // Slice-quota exceeded. Give up CPU and continue later. Remind that
        // our iteration would block the event-loop until completion if we don't
        // slice it up.
        // TODO: Use the vertx-way to enqueue a task with no synthetic delay.
        //       I couldn't find any better API than using setTimer with one
        //       millis delay. Still better than blocking the event loop.
        vertx.setTimer(1, tmrId -> iterateNextSlice());
    }

    public static interface Destination<T> {
        void onNext(T e);
        void onEnd();
    }
}
