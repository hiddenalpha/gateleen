package org.swisspush.gateleen.core.debug;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


/**
 * Decorates an {@link HttpClient} to only effectively close the client
 * if there are no more requests in progress.
 */
public class DeferCloseHttpClient implements HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(DeferCloseHttpClient.class);
    private final Vertx vertx;
    private final HttpClient delegate;
    private int countOfRequestsInProgress = 0;
    private boolean doCloseWhenDone = false;

    /**
     * See {@link DeferCloseHttpClient}.
     */
    public DeferCloseHttpClient(Vertx vertx, HttpClient delegate) {
        this.vertx = vertx;
        this.delegate = delegate;
    }

    @Override
    public HttpClientRequest request(HttpMethod method, RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest request(HttpMethod method, RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        logger.debug("({}:{}).request({}, \"{}\")", host, port, method, requestURI);
        countOfRequestsInProgress += 1;
        logger.debug("Pending request count: {}", countOfRequestsInProgress);
        HttpClientRequest request = delegate.request(method, port, host, requestURI, upstreamRsp -> {
            logger.debug("onUpstreamRsp(code={})", upstreamRsp.statusCode());
            responseHandler.handle(upstreamRsp);
            Handler<Void> originalEndHandler = getEndHandler(upstreamRsp);
            AtomicBoolean endAlreadyPublished = new AtomicBoolean(false);
            upstreamRsp.endHandler(event -> {
                logger.debug("upstreamRsp.endHandler()");
                if (!endAlreadyPublished.get()){
                    endAlreadyPublished.set(true);
                    onEndOfRequestResponseCycle();
                }
                originalEndHandler.handle(event);
            });
            Handler<Throwable> originalExceptionHandler = getExceptionHandler(upstreamRsp);
            upstreamRsp.exceptionHandler(event -> {
                logger.debug("upstreamRsp.exceptionHandler({})", event.toString());
                if (!endAlreadyPublished.get()){
                    endAlreadyPublished.set(true);
                    onEndOfRequestResponseCycle();
                }
                originalExceptionHandler.handle(event);
            });
        });
        return request;
    }

    private void onEndOfRequestResponseCycle() {
        countOfRequestsInProgress -= 1;
        logger.debug("Pending request count: {}", countOfRequestsInProgress);
        if (countOfRequestsInProgress == 0 && doCloseWhenDone) {
            logger.debug("No pending request right now. And there was a request to close. So close now.");
            doCloseWhenDone = false; // TODO: Do we need this?
            delegate.close();
        }
    }

    private Handler<Void> getEndHandler(HttpClientResponse rsp) {
        return getPrivateField(rsp, "endHandler", Handler.class);
    }

    private Handler<Throwable> getExceptionHandler(HttpClientResponse rsp) {
        return getPrivateField(rsp, "exceptionHandler", Handler.class);
    }

    private <T> T getPrivateField(HttpClientResponse rsp, String name, Class<T> type) {
        try {
            Field field = rsp.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(rsp);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest get(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest getAbs(String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest getAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient getNow(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient getNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient getNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient getNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest post(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest postAbs(String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest postAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest head(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest headAbs(String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest headAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient headNow(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient headNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient headNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient headNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest options(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest optionsAbs(String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest optionsAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient optionsNow(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient optionsNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient optionsNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient optionsNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest put(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest putAbs(String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest putAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest delete(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest deleteAbs(String absoluteURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClientRequest deleteAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocketAbs(String url, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options, MultiMap headers) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI, MultiMap headers) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI, MultiMap headers) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStreamAbs(String url, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI, MultiMap headers) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient connectionHandler(Handler<HttpConnection> handler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public HttpClient redirectHandler(Function<HttpClientResponse, Future<HttpClientRequest>> handler) {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public Function<HttpClientResponse, Future<HttpClientRequest>> redirectHandler() {
        throw new UnsupportedOperationException("TODO: Not impl yet");/*TODO*/
    }

    @Override
    public void close() {
        if (countOfRequestsInProgress > 0) {
            logger.debug("Do NOT close right now. But close as soon there are no more pending requests (pending={})", countOfRequestsInProgress);
            //
            doCloseWhenDone = true;
            return;
        }
        logger.debug("We're idle. Close right now");
        delegate.close();
    }

}
