package org.swisspush.gateleen.expansion.myReImpl;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.expansion.ExpansionHandler;
import org.swisspush.gateleen.expansion.myReImpl.ResourcetreePreOrderPublisher.Node;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleFeaturesProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling.END_WITH_SLASH;
import static org.swisspush.gateleen.routing.RuleFeatures.Feature.EXPAND_ON_BACKEND;
import static org.swisspush.gateleen.routing.RuleFeatures.Feature.STORAGE_EXPAND;
import static org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;


public class MyExpansionHandler implements RuleChangesObserver  {

    private static final String EXPAND_PARAM = "expand";
    private static final Logger LOG = LoggerFactory.getLogger(MyExpansionHandler.class);
    private static final long MAX_INFLIGHT_SUBREQUESTS = 16;
    private final Vertx vertx;
    final HttpClient httpClient;
    private RuleFeaturesProvider ruleFeaturesProvider = new RuleFeaturesProvider(new ArrayList<>());
    int maxExpansionLevelHard = Integer.MAX_VALUE;
    int maxExpansionLevelSoft = Integer.MAX_VALUE;
    /** A list of parameters, which are always removed from all requests. */
    List<String> parameter_to_remove_for_all_request;

    public MyExpansionHandler(Vertx vertx, HttpClient httpClient) {
        this.vertx = vertx;
        this.httpClient = httpClient;
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        LOG.info("Update expandOnBackend and storageExpand information from changed routing rules");
        ruleFeaturesProvider = new RuleFeaturesProvider(rules);
    }

    public boolean isZipRequest(HttpServerRequest req) {
        //LOG.warn("TODO not impl");
        return false;
    }

    public void handleZipRecursion(HttpServerRequest req) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    public boolean isExpansionRequest(HttpServerRequest req) {
        return HttpMethod.GET == req.method() && req.params().contains(EXPAND_PARAM) && !isBackendExpand(req.uri());
    }

    public void handleExpansionRecursion(HttpServerRequest downstreamReq) {
        new ExpansionRequest(downstreamReq).handleExpand();
    }

    /**
     * Check to see whether this request will be expanded by the backend (and therefore the expansionhandler
     * won't do anything).
     *
     * @param uri uri to check against the internal list of expandOnBackend urls
     * @return boolean and true if the expand should be done by the backend
     */
    private boolean isBackendExpand(String uri) {
        return ruleFeaturesProvider.isFeatureRequest(EXPAND_ON_BACKEND, uri);
    }

    /**
     * Check to see whether this request is a storageExpand request (will be expanded
     * by in the storage) (and therefore the expansionhandler won't do the subrequests
     * by itself).
     *
     * @return true if the expand should be done in storage.
     */
    protected boolean isStorageExpand(String uri) {
        return ruleFeaturesProvider.isFeatureRequest(STORAGE_EXPAND, uri);
    }

    Integer extractExpandParamValue(final HttpServerRequest request, final Logger log) {
        String expandValue = request.params().get(EXPAND_PARAM);
        log.debug("Got expand parameter value " + expandValue);

        try {
            int value = Integer.parseInt(expandValue);
            if(value < 0){
                log.warn("expand parameter value '{}' is not a positive number", expandValue);
                return null;
            }
            return value;
        } catch (NumberFormatException ex){
            log.warn("expand parameter value '{}' is not a valid number", expandValue);
            return null;
        }
    }

    /**
     * Respond the request with a statuscode {@link StatusCode#BAD_REQUEST} and body.
     *
     * @param req the request to respond to
     * @param body the body to respond
     */
    void respondBadRequest(final HttpServerRequest req, String body){
        ResponseStatusCodeLogUtil.info(req, StatusCode.BAD_REQUEST, ExpansionHandler.class);
        HttpServerResponse rsp = req.response();
        rsp.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        rsp.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        rsp.end(body);
        req.resume();
    }

    private String constructSubUri(String path, MultiMap params, String childName, ExpansionDeltaUtil.SlashHandling endWithSlash) {
        String uri = ExpansionDeltaUtil.constructRequestUri(path, params, parameter_to_remove_for_all_request, childName, endWithSlash);
        LOG.debug("Constructed uri: {}", uri);
        return uri;
    }



    private class ExpansionRequest implements Subscriber<Node> {

        private final long startMs;
        private final Logger requestLog;
        private final HttpServerRequest downstreamReq;
        private final HttpServerResponse downstreamRsp;
        private Subscription subscription;

        private ExpansionRequest(HttpServerRequest downstreamReq) {
            this.startMs = System.currentTimeMillis();
            this.requestLog = RequestLoggerFactory.getLogger(ExpansionHandler.class, downstreamReq);
            this.downstreamReq = downstreamReq;
            this.downstreamRsp = downstreamReq.response();
        }

        void handleExpand() {
            downstreamReq.pause();

            Integer expandLevel = extractExpandParamValue(downstreamReq, requestLog);
            if (expandLevel == null) {
                respondBadRequest(downstreamReq, "Expand parameter is not valid. Must be a positive number");
                return;
            }
            if (expandLevel > maxExpansionLevelHard) {
                String message = "Expand level '" + expandLevel + "' is greater than the maximum expand level '" + maxExpansionLevelHard + "'";
                requestLog.info(message);
                respondBadRequest(downstreamReq, message);
                return;
            }
            if (expandLevel > maxExpansionLevelSoft) {
                requestLog.warn("Expand level '{}' is greater than the maximum soft expand level '{}'. Using '{}' instead",
                        expandLevel, maxExpansionLevelSoft, maxExpansionLevelSoft);
                expandLevel = maxExpansionLevelSoft;
            }

            if (expandLevel > 1 && isStorageExpand(downstreamReq.uri())) {
                respondBadRequest(downstreamReq, "Expand values higher than 1 are not supported for storageExpand requests");
                return;
            }

            downstreamReq.params().remove("expand");
            final String homeUri = constructSubUri(downstreamReq.path(), downstreamReq.params(), null, END_WITH_SLASH);

            new ResourcetreePreOrderPublisher(vertx, httpClient, homeUri, downstreamReq.headers(), expandLevel)
                    .subscribe(this);
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            // Use chunked for downstream. Usually its not a good idea to collect
            // the whole subtree into memory beforehand.
            downstreamRsp.setChunked(true);
            subscription.request(MAX_INFLIGHT_SUBREQUESTS);
        }

        @Override
        public void onNext(Node res) {
            // We got one, so we order one more.
            subscription.request(1);
            String typeStr = res.isCollection() ? "Collection" : "Resource";
            LOG.debug("onNext({}, {})", res.relPath(), typeStr);
            downstreamRsp.write(typeStr + " - " + res.relPath() + "\n");
        }

        @Override
        public void onError(Throwable thr) {
            LOG.warn("Expand request failed: {}", downstreamReq.uri(), thr);
            if (downstreamRsp.headWritten()) {
                downstreamRsp.close();
            } else {
                downstreamRsp.setStatusCode(500);
                downstreamRsp.write(thr.getClass().getName() + "\n");
            }
        }

        @Override
        public void onComplete() {
            long durationMs = System.currentTimeMillis() - startMs;
            if (durationMs > 10_000) {
                LOG.info("Expand took {}ms: {}", durationMs, downstreamReq.uri());
            } else {
                LOG.trace("Expand took {}ms: {}", durationMs, downstreamReq.uri());
            }
            downstreamRsp.end();
        }
    }

}
