package org.swisspush.gateleen.expansion.myReImpl;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.expansion.ExpansionHandler;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleFeaturesProvider;

import javax.annotation.Nonnull;
import java.util.*;

import static org.swisspush.gateleen.routing.RuleFeatures.Feature.EXPAND_ON_BACKEND;
import static org.swisspush.gateleen.routing.RuleFeatures.Feature.STORAGE_EXPAND;
import static org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;


public class MyExpansionHandler implements RuleChangesObserver  {

    private static final String EXPAND_PARAM = "expand";
    private static final Logger LOG = LoggerFactory.getLogger(MyExpansionHandler.class);
    private final Vertx vertx;
    private final HttpClient httpClient;
    private RuleFeaturesProvider ruleFeaturesProvider = new RuleFeaturesProvider(new ArrayList<>());
    int maxExpansionLevelHard = Integer.MAX_VALUE;
    int maxExpansionLevelSoft = Integer.MAX_VALUE;
    /** A list of parameters, which are always removed from all requests. */
    List<String> parameter_to_remove_for_all_request;
    private final Set<ObservableEmitter<ExpansionStats>> statsObservers = new HashSet<>();

    public MyExpansionHandler(Vertx vertx, HttpClient httpClient) {
        this.vertx = vertx;
        this.httpClient = httpClient;
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        LOG.info("Update expandOnBackend and storageExpand information from changed routing rules");
        ruleFeaturesProvider = new RuleFeaturesProvider(rules);
    }

    public Disposable observeExpansionStats(@Nonnull Consumer<ExpansionStats> onNext) {
        Objects.requireNonNull(onNext, "onNext");

        // Intercept dispose calls so we're able to cleanup our observer list.
        var interceptor = new Disposable() {
            Disposable downstream;
            ObservableEmitter<ExpansionStats> emitter;
            @Override public void dispose() {
                downstream.dispose();
                statsObservers.remove(emitter);
            }
            @Override public boolean isDisposed() {
                return downstream.isDisposed();
            }
        };

        Observable<ExpansionStats> obs = Observable.create(e -> statsObservers.add(interceptor.emitter = e));
        interceptor.downstream = obs.subscribe(onNext, this::onStatsFailed);
        return interceptor;
    }

    private void onStatsFailed(Throwable thr) {
        LOG.warn("Expansion statistics failed", thr);
    }

    void publishExpansionStats(ExpansionStats stats) {
        vertx.setTimer(1, unused -> {
            for (var observer : statsObservers) {
                observer.onNext(stats);
            }
        });
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
        new ExpansionRequest(vertx, httpClient, this, downstreamReq).handleExpand();
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

    /**
     * @throws IllegalArgumentException
     *      In case expand parameter seems invalid.
     */
    int extractExpandParamValue(final HttpServerRequest request, final Logger log) {
        String expandValue = request.params().get(EXPAND_PARAM);
        log.debug("Got expand parameter value " + expandValue);

        try {
            int value = Integer.parseInt(expandValue);
            if(value < 0){
                throw new IllegalArgumentException("expand parameter value '" + expandValue + "' is not a positive number");
            }
            return value;
        } catch (NumberFormatException ex){
            throw new IllegalArgumentException("expand parameter value '" + expandValue + "' is not a valid number", ex);
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
        if (rsp.headWritten()) {
            LOG.debug("Cannot send 'Bad Request': Header got already sent.");
            rsp.close();
        } else {
            rsp.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            rsp.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            rsp.headers().set("Content-Type", "text/plain");
            rsp.headers().set("Content-Length", String.valueOf(body.length() + 1));
            rsp.write(body); /*TODO test*/
            rsp.end("\n");
        }
        req.resume();
    }


    /** IMMUTABLE DTO to publish statistics about expansion. */
    public static class ExpansionStats {
        private final String uri;
        private final long durationMs;

        public ExpansionStats(String uri, long durationMs) {
            this.uri = uri;
            this.durationMs = durationMs;
        }

        public String uri(){ return uri; }

        public long durationMs() { return durationMs; }
    }

}
