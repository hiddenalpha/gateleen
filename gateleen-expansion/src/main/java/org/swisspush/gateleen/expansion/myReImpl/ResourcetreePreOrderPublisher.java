package org.swisspush.gateleen.expansion.myReImpl;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.disposables.Disposable;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.vertx.core.http.HttpMethod.GET;
import static org.swisspush.gateleen.expansion.myReImpl.ResourcetreePreOrderPublisher.Node;


/**
 * Iterates the resource tree named by specified URL using a pre-order traversal.
 */
public class ResourcetreePreOrderPublisher extends Flowable<Node> {

    private static final int SUB_REQ_TIMEOUT_MS = 120000;
    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final Pattern PAT_URI = Pattern.compile("^(?<url>[^?]+)(?:\\?(?<query>[^?]*))?$");
    private static final Logger LOG = LoggerFactory.getLogger(ResourcetreePreOrderPublisher.class);
    private final Queue<Runnable> tasks;
    private final HttpClient httpClient;
    /* Without trailing slash */
    private final String url;
    private final String query;
    private final MultiMap headers;
    private final int maxdepth;

    /**
     * Create a {@link ResourcetreePreOrderPublisher}.
     * @param uri
     *      The URI in the resource tree to perform the iteration within.
     * @param headers
     *      The headers to use for requests perfomed in scope of the iteration.
     * @param maxdepth
     *      Maximum depth to iterate into the tree.
     */
    public ResourcetreePreOrderPublisher(Vertx vertx, HttpClient httpClient, String uri, MultiMap headers, int maxdepth) {
        this.tasks = new ArrayDeque<>();
        this.httpClient = httpClient;
        Matcher m = PAT_URI.matcher(uri);
        if (!m.matches()) {
            throw new IllegalArgumentException("Failed to parse uri: " + uri);
        }
        String url = m.group("url");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.url = url;
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

        private Subscriber<? super Node> subscriber;
        private final AtomicBoolean isrunning = new AtomicBoolean(false);
        private final AtomicLong remainingRequests = new AtomicLong(0);
        private volatile boolean cancelRequest = false;
        private Disposable disposable;

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
            if (disposable != null) {
                disposable.dispose();
            }
        }

        private void start() {
            disposable = new RecursionLevel(url, headers).asFlowable()
                    // I did like to use: flowable.subscribe(subscriber)
                    // But then it throws like "Can only subscribe once" exception. Using this
                    // other overload, it seems to work as expected :-p
                    .subscribe(subscriber::onNext, subscriber::onError, subscriber::onComplete);
        }

    }



    private class RecursionLevel {
        /** Our parent recursion level */
        private final RecursionLevel parent;
        /** URL for this recursion level */
        private final String url;
        /** HTTP request headers to apply to outgoing requests */
        private final MultiMap headers;
        /** Index of this node in the parents child list */
        private final int thisIdx;
        /** Current recursion level */
        private final int level;
        private Node node;
        private final AtomicInteger pendingChilds = new AtomicInteger(0);
        private final AtomicInteger iChild = new AtomicInteger(0);
        /** Index where this node here is in the parent */
        private HttpClientRequest req;
        private List<String> childNames;
        private FlowableEmitter<Node> emitter;

        public RecursionLevel(String url, MultiMap headers) {
            this(null, url, headers, 0, 1);
        }

        private RecursionLevel(RecursionLevel parent, String url, MultiMap headers, int thisIdx, int level) {
            this.parent = parent;
            this.url = url;
            this.headers = headers;
            this.thisIdx = thisIdx;
            this.level = level;
        }

        private Flowable<Node> asFlowable() {
            // Due to using an unbound buffer here, there is no real backpressure. Implementing
            // real backpressure would require to write more code. But theory says we MUST NOT
            // write more code if shorter code can do it.
            // If we like to implement REAL backpressure, please open an issue to request its
            // implementation.
            return Flowable.create(this::onEmitter, BackpressureStrategy.BUFFER);
        }

        private <T> void onEmitter(FlowableEmitter<Node> emitter) {
            if (this.emitter != null) {
                // I guess should not happen. But could not yet find any useful hint in
                // doc how many times we get called.
                throw new IllegalStateException("Unexpectedly got yet another emitter");
            }
            // Setup emitter
            this.emitter = emitter;
//            emitter.setCancellable(this::onCancel); // TODO why does this call the callback immediately?
            // Initiate request.
            req = httpClient.request(GET, url +'?'+ query, this::onResponse);
            req.exceptionHandler(emitter::onError);
            req.setTimeout(SUB_REQ_TIMEOUT_MS);
            if (headers != null) {
                req.headers().setAll(headers);
            }
            req.headers().set("Accept", "application/json");
            req.headers().set(SELF_REQUEST_HEADER, "true");
            req.end();
        }

        private void onResponse(HttpClientResponse rsp) {
            //assert(this.rsp == null);
            int status = rsp.statusCode();
            if (status >= 200 && status <= 299) {
                rsp.exceptionHandler(emitter::onError);
                rsp.bodyHandler(this::onResponseBody);
            } else {
                emitter.onError(new HttpStatusException(status));
            }
        }

        private void onResponseBody(Buffer bodyBuf) {
            JsonObject bodyJson;
            try {
                bodyJson = new JsonObject(bodyBuf);
            } catch (DecodeException e) {
                emitter.onError(new DecodeException("Json parse failed for: " + url, e));
                return;
            }
            Iterator<Map.Entry<String, Object>> it = bodyJson.iterator();
            if (!it.hasNext()) {
                LOG.trace("Too few entries. Assume document: {}", url);
                publishDocumentResource(url, thisIdx, bodyBuf, bodyJson);
                emitter.onComplete();
                return;
            }
            Map.Entry<String, Object> entry = it.next();
            Object valueObj = entry.getValue();
            if (!(valueObj instanceof JsonArray)) {
                LOG.trace("Not an array. Assume document: {}", url);
                publishDocumentResource(url, thisIdx, bodyBuf, bodyJson);
                emitter.onComplete();
                return;
            }
            // Make sure list only contains expected types.
            for (Object childObj : ((JsonArray) valueObj).getList()) {
                if (!(childObj instanceof String)) {
                    emitter.onError(new ClassCastException("String expected but got " + childObj.getClass().getSimpleName() + " for: " + url));
                    return;
                }
            }
            childNames = ((JsonArray) valueObj).getList();
            if (it.hasNext()) {
                childNames = null; // GC
                LOG.trace("Too many entries. Assume document: {}", url);
                publishDocumentResource(url, thisIdx, bodyBuf, bodyJson);
                emitter.onComplete();
                return;
            }

            // PreOrder iteration. So publish ourself
            node = publishCollectionResource(url, thisIdx);

            // Then consider children.
            if (level > maxdepth) {
                LOG.debug("Maxdepth of {} reached.", maxdepth);
                emitter.onComplete();
                return;
            }else{
                LOG.trace("Process childs at level {}/{}", level, maxdepth);
                // +1 to ensure we never reach zero while iteration still running. Will be
                // decremented again after loop has finished.
                pendingChilds.incrementAndGet();
                // Trigger the 1st one.
                iterateNextChild();
            }
        }

        private DirNode parentNode() {
            return (parent == null) ? null : (DirNode) parent.node;
        }

        private void iterateNextChild() {
            int i = iChild.getAndIncrement();
            if(i < childNames.size()){
                String childName = childNames.get(i);
                childNames.set(i, null); // Think for GC
                LOG.trace("Process child {} in {}", i, url);
                String subUrl = url.endsWith("/") ? (url + childName) : (url + '/' + childName);
                pendingChilds.incrementAndGet();
                new RecursionLevel(this, subUrl, headers, i, level + 1).asFlowable()
                        .subscribe(emitter::onNext, emitter::onError, this::onChildDone);
            }else{
                // We call this for TWO reasons:
                //  1. We did increment by one before loop start to prevent reaching zero
                //     while iteration. Now its time to remove that headroom.
                //  2. In case there are ZERO children, this also will call onComplete for us.
                onChildDone();
            }
        }

        private void onChildDone() {
            int remaining = pendingChilds.decrementAndGet();
            if (remaining > 0) {
                iterateNextChild();
            } else if (remaining == 0) {
                emitter.onComplete();
            } else {
                throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
            }
        }

        private DirNode publishCollectionResource(String url, int childIdx) {
            int relPathOffs = ResourcetreePreOrderPublisher.this.url.length();
            DirNode elem = new DirNode(parentNode(), url, relPathOffs, childIdx, level);
            emitter.onNext(elem);
            return elem;
        }

        private void publishDocumentResource(String url, int childIdx, Buffer bodyBuf, JsonObject bodyJson) {
            emitter.onNext(new LeaveNode(parentNode(), url, ResourcetreePreOrderPublisher.this.url.length(),
                    childIdx, level, bodyBuf, bodyJson));
        }

    }



    public static abstract class Node {
        private final DirNode parent;
        private final int relPathOffs;
        private final int childIdx;
        private final int level;
        private String absPath;
        private String relPath;
        private String basename;

        private Node(DirNode parent, String absPath, int relPathOffs, int childIdx, int level) {
            this.parent = parent;
            this.absPath = Objects.requireNonNull(absPath);
            this.relPathOffs = relPathOffs;
            this.childIdx = childIdx;
            this.level = level;
        }

        public abstract boolean isCollection();

        public boolean isDocument(){ return !isCollection(); }

        public DirNode parent(){ return parent; }

        /** Index of this child in the parents node child list */
        public int childIdx(){ return childIdx; }

        /** Recursion level of this node */
        public int level(){ return level; }

        /** Absolute path including the part from the original URL (Eg: '/your/v1/api/foo/bar') */
        public String absPath() {
            if (absPath.endsWith("/")) {
                int lastNonSlash = absPath.length();
                for (; lastNonSlash >= 0 && absPath.charAt(lastNonSlash-1) == '/'; --lastNonSlash) ;
                if (lastNonSlash != absPath.length()) {
                    // Remove trailing slashes.
                    absPath = absPath.substring(0, lastNonSlash);
                }
            }
            return absPath;
        }

        /** Path starting from level where the expand request originated (Eg: '/foo/bar') */
        public String relPath() {
            if (relPath == null) {
                // No garbage created yet representing the relative path. Create now
                // lazily because caller seems to really need it.
                final String absPath = absPath();
                int start = relPathOffs;
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

    }

    public static class DirNode extends Node {
        private DirNode(DirNode parent, String absPath, int relPathOffs, int childIdx, int level) {
            super(parent, absPath, relPathOffs, childIdx, level);
        }

        @Override public boolean isCollection() { return true; }
    }

    public static class LeaveNode extends Node {
        private final Buffer bodyBuf;
        private final JsonObject bodyJson;

        private LeaveNode(DirNode parent, String absPath, int relPathOffs, int childIdx, int level, Buffer bodyBuf, JsonObject bodyJson) {
            super(parent, absPath, relPathOffs, childIdx, level);
            this.bodyBuf = bodyBuf;
            this.bodyJson = bodyJson;
        }

        @Override public boolean isCollection() { return false; }

        public Buffer bodyAsBuffer(){ return bodyBuf; }

        public JsonObject bodyAsJson(){ return bodyJson; }
    }

}
