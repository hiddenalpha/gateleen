package org.swisspush.gateleen.core.debug;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

/**
 * <p>This class got introduced to trace timings of "/xx/yy/info" requests. It is
 * optimized for exactly this purpose AND NOTHING ELSE! It was introduced because
 * SDCISA-13746 is only observable on our PROD environment. It does not reproduce
 * locally, and not on any other of our environments. So we do not really have
 * another choice but tracing down this bug directly on PROD itself. Unluckily
 * this is not that simple to do. First debugging/testing on PROD env always has
 * some risk. Plus, also our feedback-loop is terribly long due to our heavyweight
 * deployment process. Further, this deployment process makes it hard to bring
 * anything into production which is not released by upstream directly. So working
 * on a fork or similar is hard to accomplish due to those restrictions.</p>
 *
 * <p>This interface is intended so that we can emit very specific events to our
 * logging/tracing/monitoring implementation, which then hopefully will give us
 * more insight where our issue comes from. Likely we can remove it again once
 * we found the root cause.</p>
 *
 * <p>HINT: Changing or removing this class (including its usage) will break
 * privately hosted paisa/houston project.</p>
 */
public interface InfoRequestTracer {

    public void onWritingHttpResponseBegin(Vertx vertx, HttpServerRequest req);

    public void onWritingHttpResponseHasReturned(Vertx vertx, HttpServerRequest req);

    public void onWritingHttpResponseEnd(Vertx vertx, Throwable ex, HttpServerRequest req);

}
