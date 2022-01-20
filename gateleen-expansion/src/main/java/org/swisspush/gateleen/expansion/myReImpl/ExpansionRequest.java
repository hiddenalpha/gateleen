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
import org.swisspush.gateleen.expansion.myReImpl.MyExpansionHandler.ExpansionStats;
import org.swisspush.gateleen.expansion.myReImpl.ResourcetreePreOrderPublisher.Node;

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
    private int expandLevel;
    private Subscription subscription;
    private int previousLevel;

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
        try {
            expandLevel = expansionHandler.extractExpandParamValue(downstreamReq, requestLog);
        } catch (IllegalArgumentException ex) {
            expansionHandler.respondBadRequest(downstreamReq, ex.getMessage());
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

        // Somehow the old impl had the "interesting behavior" that level ONE means
        // to publish TWO levels. So we adjust this to keep backward compatibility.
        expandLevel += 1;

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
        downstreamRsp.headers().set("Content-Type", "application/json");
        subscription.request(16);
    }

    private void onNext(Node node) {
        LOG.trace("onNext({}, {})", node.getClass().getSimpleName(), node.relPath());
        // We got one, so we order another one.
        subscription.request(1);

        // Prepare level and separators of our container JSON.
        closeDeeperLevels(node.level());
        if (levelHasIncreased(node)) {
            if (is2ndOrLaterChild(node)) {
                downstreamRsp.write(",");
            }
            openRecursionLevel(node);
            // Now we're ready to append child after this condition.
        } else if (isSameLevelAsPreviousNode(node)) {
            // Just add plain separator so we can append next element after this condition.
            downstreamRsp.write(",");
        } else {
            assert(false); // MUST NOT reach this branch
        }

        // Append the effective child.
        writeKeyAsString(node);
        if (isNodeAllowedToWriteBody(node)) {
            downstreamRsp.write(":");
            downstreamRsp.write(((ResourcetreePreOrderPublisher.LeaveNode) node).bodyAsBuffer());
        }
        previousLevel = node.level();
    }

    private boolean isNodeAllowedToWriteBody(Node node) { return node.level() < expandLevel && node.isDocument(); }

    private boolean is2ndOrLaterChild(Node node) { return node.childIdx() > 0; }

    private boolean levelHasIncreased(Node node) { return node.level() > previousLevel; }

    private boolean isRootLevel(Node node) { return node.level() == 1; }

    private boolean isSameLevelAsPreviousNode(Node node) { return node.level() == previousLevel; }

    private boolean isMaxExpandLevelExceeded(int level) { return level > expandLevel; }

    private void closeDeeperLevels(int newLevel) {
        for (; newLevel < previousLevel; --previousLevel) {
            // Close previous (deeper) collections if any.
            if (isMaxExpandLevelExceeded(previousLevel)) {
                // In case of cut-off levels due to expand=X limit, innermost level is an
                // array instead an object.
                downstreamRsp.write("]");
            }else{
                downstreamRsp.write("}");
            }
        }
    }

    private void openRecursionLevel(Node node) {
        if(isRootLevel(node)) {
            // No prefix wanted on root level.
        }else{
            downstreamRsp.write(":");
        }
        if(isMaxExpandLevelExceeded(node.level())){
            // In case level gets cut due to expand=X limit, the innermost level is no
            // longer an object but an array containing keys only.
            downstreamRsp.write("[");
        }else{
            downstreamRsp.write("{");
        }
    }

    private void writeKeyAsString(Node node) {
        downstreamRsp.write("\"");
        downstreamRsp.write(node.basename().replace("\"", "\\\""));
        downstreamRsp.write("\"");
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
        closeDeeperLevels(0);
        downstreamRsp.end();
        long durationMs = System.currentTimeMillis() - startMs;
        ExpansionStats stats = new ExpansionStats(downstreamReq.uri(), durationMs);
        expansionHandler.publishExpansionStats(stats);
    }

    private String constructSubUri(String path, MultiMap params) {
        String uri = ExpansionDeltaUtil.constructRequestUri(path, params,
                expansionHandler.parameter_to_remove_for_all_request, null, END_WITHOUT_SLASH);
        LOG.debug("Constructed uri: {}", uri);
        return uri;
    }

}
