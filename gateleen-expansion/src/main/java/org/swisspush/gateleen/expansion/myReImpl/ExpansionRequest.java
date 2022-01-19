package org.swisspush.gateleen.expansion.myReImpl;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.expansion.ExpansionHandler;

import static org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling.END_WITHOUT_SLASH;


class ExpansionRequest {

    private static final Logger LOG = LoggerFactory.getLogger(ExpansionRequest.class);
    private final MyExpansionHandler expansionHandler;
    private final Vertx vertx;
    private final HttpClient httpClient;
    private final long startMs;
    private final Logger requestLog;
    private final HttpServerRequest downstreamReq;
    private final HttpServerResponse downstreamRsp;
    private Subscription subscription;
    private int previousLevel;
    private int iChild;

    ExpansionRequest(Vertx vertx, HttpClient httpClient, MyExpansionHandler expansionHandler, HttpServerRequest downstreamReq) {
        this.vertx = vertx;
        this.httpClient = httpClient;
        this.expansionHandler = expansionHandler;
        this.startMs = System.currentTimeMillis();
        this.requestLog = RequestLoggerFactory.getLogger(ExpansionHandler.class, downstreamReq);
        this.downstreamReq = downstreamReq;
        this.downstreamRsp = downstreamReq.response();
    }

    void handleExpand() {
        Integer expandLevel = expansionHandler.extractExpandParamValue(downstreamReq, requestLog);
        if (expandLevel == null) {
            expansionHandler.respondBadRequest(downstreamReq, "Expand parameter is not valid. Must be a positive number");
            return;
        }
        if (expandLevel > expansionHandler.maxExpansionLevelHard) {
            String message = "Expand level '" + expandLevel + "' is greater than the maximum expand level '"
                    + expansionHandler.maxExpansionLevelHard + "'";
            requestLog.info(message);
            expansionHandler.respondBadRequest(downstreamReq, message);
            return;
        }
        if (expandLevel > expansionHandler.maxExpansionLevelSoft) {
            requestLog.warn("Expand level '{}' is greater than the maximum soft expand level '{}'. Using '{}' instead",
                    expandLevel, expansionHandler.maxExpansionLevelSoft, expansionHandler.maxExpansionLevelSoft);
            expandLevel = expansionHandler.maxExpansionLevelSoft;
        }

        if (expandLevel > 1 && expansionHandler.isStorageExpand(downstreamReq.uri())) {
            expansionHandler.respondBadRequest(downstreamReq, "Expand values higher than 1 are not supported for storageExpand requests");
            return;
        }

        downstreamReq.params().remove("expand");
        final String homeUri = constructSubUri(downstreamReq.path(), downstreamReq.params());

        new ResourcetreePreOrderPublisher(vertx, httpClient, homeUri, downstreamReq.headers(), expandLevel)
                .subscribe(this::onNext, this::onError, this::onComplete, this::onSubscribe);
    }

    private void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        // Use chunked for downstream. Usually its not a good idea to collect
        // the whole subtree into memory beforehand.
        downstreamRsp.setChunked(true);
        iChild = 0;
        subscription.request(16);
    }

    private void onNext(ResourcetreePreOrderPublisher.Node node) {
        // TODO maxdepth produces corrupt JSON
        LOG.trace("onNext({}, {})", node.getClass().getSimpleName(), node.relPath());
        // We got one, so we order another one.
        subscription.request(1);
        for (; node.level() < previousLevel; --previousLevel) {
            // Close previous (deeper) collections if any.
            downstreamRsp.write("}");
        }
        if (node.level() > previousLevel) {
            if (node.childIdx() > 0) {
                // Every except the 1st child need a comma as separator to the previous node.
                downstreamRsp.write(",");
            }
            // Go down one level
            downstreamRsp.write("{");
        } else if (node.level() == previousLevel) {
            // Add another child to the same node
            downstreamRsp.write(",");
        } else {
            assert(false); // MUST NOT reach this branch
        }

        downstreamRsp.write("\"");
        downstreamRsp.write(node.basename().replace("\"", "\\\""));
        downstreamRsp.write("\":");
        if (node.isDocument()) {
            downstreamRsp.write(((ResourcetreePreOrderPublisher.LeaveNode) node).body());
        }
        previousLevel = node.level();
    }

    private void onError(Throwable thr) {
        LOG.warn("Expand request failed: {}", downstreamReq.uri(), thr);
        if (downstreamRsp.headWritten()) {
            downstreamRsp.close();
        } else {
            downstreamRsp.setStatusCode(500);
            downstreamRsp.write("Request failed (fc601daceb6d7df1f601f41be54ddd01):\n")
                    .write(thr.getClass().getName()).write("\n");
        }
    }

    private void onComplete() {
        while (previousLevel-- > 0) {
            // Finalize the not yet closed levels.
            downstreamRsp.write("}");
        }
        downstreamRsp.end();
        long durationMs = System.currentTimeMillis() - startMs;
        if (durationMs > 30_000) {
            LOG.info("Expand took {}ms: {}", durationMs, downstreamReq.uri());
        } else {
            LOG.debug("Expand took {}ms: {}", durationMs, downstreamReq.uri());
        }
    }

    private String constructSubUri(String path, MultiMap params) {
        String uri = ExpansionDeltaUtil.constructRequestUri(path, params,
                expansionHandler.parameter_to_remove_for_all_request, null, END_WITHOUT_SLASH);
        LOG.debug("Constructed uri: {}", uri);
        return uri;
    }

}
