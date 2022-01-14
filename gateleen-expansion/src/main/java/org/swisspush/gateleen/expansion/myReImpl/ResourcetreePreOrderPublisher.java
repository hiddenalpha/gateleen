package org.swisspush.gateleen.expansion.myReImpl;

import io.reactivex.Flowable;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.vertx.core.http.HttpMethod.GET;
import static org.swisspush.gateleen.expansion.myReImpl.ResourcetreePreOrderPublisher.Node;


public class ResourcetreePreOrderPublisher extends Flowable<Node> {

    private static final int SUB_REQ_TIMEOUT_MS = 120000;
    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final Pattern PAT_URI = Pattern.compile("^(?<url>[^?]+)(?:\\?(?<query>[^?]*))?$");
    private static final Logger LOG = LoggerFactory.getLogger(ResourcetreePreOrderPublisher.class);
    private final HttpClient httpClient;
    private final String url;
    private final String query;
    private final MultiMap headers;
    private final int maxdepth;

    public ResourcetreePreOrderPublisher(Vertx vertx, HttpClient httpClient, String uri, MultiMap headers, int maxdepth) {
        this.httpClient = httpClient;
        Matcher m = PAT_URI.matcher(uri);
        if (!m.matches()) {
            throw new IllegalArgumentException("Failed to parse uri: " + uri);
        }
        this.url = m.group("url");
        String query = m.group("query");
        this.query = (query == null) ? "" : query;
        this.headers = headers;
        this.maxdepth = maxdepth;
    }

    @Override
    protected void subscribeActual(Subscriber<? super Node> subscriber) {
        subscriber.onSubscribe(new SubscriptionImpl(subscriber));
    }



    private class SubscriptionImpl implements Subscription {

        private final Subscriber<? super Node> subscriber;
        private final AtomicBoolean isrunning = new AtomicBoolean(false);
        private volatile boolean cancelRequest = false;
        private final AtomicLong remainingRequests = new AtomicLong(0);

        public SubscriptionImpl(Subscriber<? super Node> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("MUST NOT request less than one: " + n));
                return;
            }
            if(n == Long.MAX_VALUE){
                // Special value which means 'please stream infinitely' (see interface doc).
                remainingRequests.set(Long.MAX_VALUE);
                if (!isrunning.getAndSet(true)) {
                    this.start();
                }
                return;
            }
            // Increase by how much subscriber wants.
            long newVal = remainingRequests.addAndGet(n);
            if (newVal < 0) {
                subscriber.onError(new ArithmeticException("Overflow: " + (newVal - n) + " + " + n + ""));
                return;
            }
            if (!isrunning.getAndSet(true)) {
                this.start();
            }
        }

        @Override
        public void cancel() {
            cancelRequest = true;
        }

        private void start() {
            new RecursionLevel(this, url, headers, subscriber).run(this::onChildDone);
        }

        private void onChildDone() {
            subscriber.onComplete();
        }

    }



    private class RecursionLevel {
        private final SubscriptionImpl subscription;
        private final RecursionLevel parent;
        private final Subscriber<? super Node> subscriber;
        private final String url;
        private final MultiMap headers;
        private final int level;
        private Runnable onDone;
        private HttpClientRequest req;
        private HttpClientResponse rsp;
        private List<String> childNames;
        private int iChild = 0;

        public RecursionLevel(SubscriptionImpl subscription, String url, MultiMap headers, Subscriber<? super Node> subscriber) {
            this(subscription, null, url, headers, 1, subscriber);
        }

        private RecursionLevel(SubscriptionImpl subscription, RecursionLevel parent, String url, MultiMap headers, int level, Subscriber<? super Node> subscriber) {
            this.subscription = subscription;
            this.parent = parent;
            this.subscriber = subscriber;
            this.url = url;
            this.headers = headers;
            this.level = level;
        }

        private void run(Runnable onDone) {
            if (subscription.cancelRequest) {
                LOG.debug("Stream got canceled");
                return;
            }
            this.onDone = onDone;
            req = httpClient.request(GET, url +'?'+ query, this::onResponse);
            req.setTimeout(SUB_REQ_TIMEOUT_MS);
            //req.setChunked(true);
            if (headers != null) {
                req.headers().setAll(headers);
            }
            req.headers().set("Accept", "application/json");
            req.headers().set(SELF_REQUEST_HEADER, "true");
            req.exceptionHandler(subscriber::onError);
            req.end();
        }

        private void onResponse(HttpClientResponse rsp) {
            if (subscription.cancelRequest) {
                LOG.debug("Stream got canceled");
                return;
            }
            this.rsp = rsp;
            int status = rsp.statusCode();
            if (status >= 200 && status <= 299) {
                rsp.exceptionHandler(subscriber::onError);
                rsp.bodyHandler(this::onResponseBody);
            } else {
                subscriber.onError(new HttpStatusException(status));
            }
        }

        private void onResponseBody(Buffer bodyBuf) {
            if (subscription.cancelRequest) {
                LOG.debug("Stream got canceled");
                return;
            }
            JsonObject bodyJson;
            try {
                bodyJson = new JsonObject(bodyBuf);
            } catch (DecodeException e) {
                subscriber.onError(new DecodeException("Json parse failed for: " + url, e));
                return;
            }
            Iterator<Map.Entry<String, Object>> it = bodyJson.iterator();
            if (!it.hasNext()) {
                LOG.trace("Assume is NOT collection: {}", url);
                publishDocumentResource(url, bodyBuf);
                if (onDone != null) onDone.run();
                return;
            }
            Map.Entry<String, Object> entry = it.next();
            String key = entry.getKey();
            Object valueObj = entry.getValue();
            if (!(valueObj instanceof JsonArray)) {
                LOG.trace("Assume is NOT collection: {}", url);
                publishDocumentResource(url, bodyBuf);
                if (onDone != null) onDone.run();
                return;
            }
            // Make sure list only contains expected types.
            for (Object childObj : ((JsonArray) valueObj).getList()) {
                if (!(childObj instanceof String)) {
                    subscriber.onError(new ClassCastException("String expected but got " + childObj.getClass().getSimpleName() + " for: " + url));
                    return;
                }
            }
            childNames = ((JsonArray) valueObj).getList();
            if (it.hasNext()) {
                subscriber.onError(new IllegalArgumentException("Too many fields in collection response: " + url));
                return;
            }
            processNextEntry();
        }

        private void processNextEntry() {
            if (subscription.cancelRequest) {
                LOG.debug("Stream got canceled");
                return;
            }
            if (!reduceRemainingDemand()) {
                return; // Don't produce more. subscriber already satisfied.
            }
            final int i = iChild++;
            if (i == 0) {
                // PreOrder iteration. So publish ourself (except the root node).
                // TODO Handle case when we ourself are a resource.
                publishCollectionResource(url, null);
            }
            // Take a look at maxdepth
            if (level > maxdepth) {
                LOG.debug("Maxdepth of {} reached.", maxdepth);
                if (onDone != null) onDone.run();
                return;
            } else {
                LOG.trace("Reached level {} of {}", level, maxdepth);
            }
            // Then go on with childs.
            if (i < childNames.size()) {
                LOG.trace("Process child {} in {}", i, url);
                String childName = childNames.get(i);
                childNames.set(i, null); // Be kind to GC
                String subUrl = url.endsWith("/") ? (url + childName) : (url + '/' + childName);
                new RecursionLevel(subscription, this, subUrl, headers, level + 1, subscriber).run(this::onChildDone);
            } else {
                LOG.trace("All childs streamed within {}", url);
                if (onDone != null) {
                    onDone.run();
                }
            }
        }

        private boolean reduceRemainingDemand() {
            while (true) {
                long demand = subscription.remainingRequests.get();
                if (demand < 1) {
                    return false; // Nothing we could decrement.
                }
                if (subscription.remainingRequests.compareAndSet(demand, demand - 1)) {
                    return true; // Successfully decremented
                }
                // Atomic update failed. Try again.
            }
        }

        private void publishCollectionResource(String url, Buffer body) {
            if (subscription.cancelRequest) {
                LOG.debug("Stream got canceled");
                return;
            }
            subscriber.onNext(new Node(url, url.length() - ResourcetreePreOrderPublisher.this.url.length(), true, null));
        }

        private void publishDocumentResource(String url, Buffer body) {
            if (subscription.cancelRequest) {
                LOG.debug("Stream got canceled");
                return;
            }
            subscriber.onNext(new Node(url, url.length() - ResourcetreePreOrderPublisher.this.url.length(), false, body));
        }

        private void onChildDone() {
            while(true) {
                if (subscription.cancelRequest) {
                    LOG.debug("Stream got canceled");
                    return;
                }
                long oldVal = subscription.remainingRequests.get();
                if(oldVal == Long.MAX_VALUE){
                    LOG.trace("Unbound. Continue streaming.");
                    processNextEntry();
                    return;
                }
                if(oldVal <= 0){
                    LOG.debug("Requested amount provided. Pause stream.");
                    return;
                }
                long newVal = oldVal - 1;
                if (subscription.remainingRequests.compareAndSet(oldVal, newVal)) {
                    LOG.trace("Still elements wanted. Go ahead.");
                    processNextEntry();
                    return;
                }else{
                    LOG.debug("Atomic value has changed in meantime. Need to try again.");
                    // loop
                }
            }
        }

    }



    public static class Node {
        private final int relPathLength;
        private final boolean isCollection;
        private final Buffer body;
        private String absPath;
        private String relPath;
        private String basename;

        public Node(String absPath, int relPathLength, boolean isCollection, Buffer body) {
            this.absPath = Objects.requireNonNull(absPath);
            this.relPathLength = relPathLength;
            this.isCollection = isCollection;
            this.body = body;
        }

        /** Absolute path including the part from the original URL (Eg: '/your/v1/api/foo/bar') */
        public String absPath() {
            int lastNonSlash = absPath.length();
            for (; lastNonSlash >= 0 && absPath.charAt(lastNonSlash-1) == '/'; --lastNonSlash) ;
            if (lastNonSlash != absPath.length()) {
                // Remove trailing slashes.
                absPath = absPath.substring(0, lastNonSlash);
            }
            return absPath;
        }

        /** Path starting from level where the expand request originated (Eg: '/foo/bar') */
        public String relPath() {
            if (relPath == null) {
                // No garbage created yet representing the relative path. Create now
                // lazily because caller seems to really need it.
                final String absPath = absPath();
                int start = absPath.length() - relPathLength;
                int end = absPath.length();
                if (start < 0) start = 0;
                relPath = (end - start == 0) ? "/" : absPath.substring(start, end);
            }
            return relPath;
        }

        /** Same idea as <a href="https://man7.org/linux/man-pages/man1/basename.1.html">basename</a> (Eg 'bar') */
        public String basename() {
            if (basename == null) {
                String absPath = absPath();
                int startOfLastSegm = absPath.lastIndexOf('/');
                basename = (startOfLastSegm == -1) ? absPath : absPath.substring(startOfLastSegm + 1);
            }
            return basename;
        }

        public boolean isCollection() {
            return isCollection;
        }

        public Buffer body() {
            return body;
        }
    }

}
