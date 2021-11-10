package murks.SDCISA_7235;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.System.nanoTime;


public class TimeTrace {

    private static Map<String, ZoneSummary> methods = new HashMap<>(1024);
    private static final AtomicReference<Thread> thrd = new AtomicReference<>();

    public static Zone zoneEnter(String key) {
        ensureSameThread();
        Zone zone = new Zone(key);
        zone.enterNs = nanoTime();
        zone.isEntered = true;
        return zone;
    }

    /** Originall I used this class in vertx context. So I had to do with one
     * thread only (lucky guy). I just wrote this as some kind of assert that
     * I can be sure no unexpected multi-threading stuff would hit me. */
    private static void ensureSameThread() {
        Thread alreadyKnown = thrd.get();
        Thread current = Thread.currentThread();
        if (alreadyKnown == null) {
            boolean ok = thrd.compareAndSet(alreadyKnown, current);
            if (!ok) throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
        } else if (alreadyKnown != current) {
            throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
        }
    }

    public static void finalizeResponse() {
        ensureSameThread();
        ZoneSummary[] zoneSummaryArr = methods.values().toArray(new ZoneSummary[0]);
        Arrays.sort(zoneSummaryArr, (a, b) -> Long.compare(b.totlExecTimeNs, a.totlExecTimeNs));
        System.out.println("\nTimeTracing from "+ LocalDateTime.now());
        for (ZoneSummary zoneSummary : zoneSummaryArr) {
            System.out.println(String.format("%9d - %s", zoneSummary.totlExecTimeNs/1000000, zoneSummary.key));
        }
        System.out.println();
    }

    public static class Zone {
        final String key;
        long enterNs;
        boolean isEntered;

        private Zone(String key){ this.key = key; }

        public void zoneExit() {
            ensureSameThread();
            if ( ! isEntered) throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
            ZoneSummary zoneSummary = methods.computeIfAbsent(key, ZoneSummary::new);
            long methodLeave = nanoTime();
            long duration = methodLeave - enterNs;
            if (duration < 0) throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
            zoneSummary.totlExecTimeNs += duration;
        }
    }

    static class ZoneSummary {
        final String key;
        volatile long totlExecTimeNs;

        public ZoneSummary(String key) { this.key = key; }
    }

}
