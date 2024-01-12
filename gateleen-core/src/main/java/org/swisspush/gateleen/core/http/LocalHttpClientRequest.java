package org.swisspush.gateleen.core.http;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpFrame;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.StreamPriority;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.LanguageHeader;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import org.slf4j.Logger;

import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Bridges a HttpClientRequest to a HttpServerRequest sent to a request handler.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class LocalHttpClientRequest extends BufferBridge implements FastFailHttpClientRequest {
    private static final Logger log = getLogger(LocalHttpClientRequest.class);
    private final Vertx vertx;
    private MultiMap headers = new HeadersMultiMap();
    private MultiMap params;
    private HttpMethod method;
    private String uri;
    private String path;
    private String query;
    private LocalHttpServerResponse serverResponse;
    private final LocalHttpConnection connection;
    private Handler<RoutingContext> routingContextHandler;
    private boolean bound = false;

    private static final SocketAddress address = new SocketAddressImpl(0, "localhost");

    private HttpServerRequest serverRequest = new HttpServerRequestInternal() {

        @Override
        public HttpVersion version() {
            return HttpVersion.HTTP_1_0;
        }

        @Override
        public HttpMethod method() {
            return method;
        }

        @Override
        public boolean isSSL() {
            return false;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String path() {
            if (path == null) {
                path = UriParser.path(uri());
            }
            return path;
        }

        @Override
        public String query() {
            if (query == null) {
                query = UriParser.query(uri());
            }
            return query;
        }

        @Override
        public MultiMap params() {
            if (params == null) {
                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri());
                Map<String, List<String>> prms = queryStringDecoder.parameters();
                params = new HeadersMultiMap();
                if (!prms.isEmpty()) {
                    for (Map.Entry<String, List<String>> entry : prms.entrySet()) {
                        params.add(entry.getKey(), entry.getValue());
                    }
                }
            }
            return params;
        }

        @Override
        public String getParam(String paramName) {
            return params().get(paramName);
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        @Override
        public String getHeader(String headerName) {
            return headers().get(headerName);
        }

        @Override
        public String getHeader(CharSequence headerName) {
            return headers().get(headerName);
        }

        @Override
        public SocketAddress remoteAddress() {
            return address;
        }

        @Override
        public SocketAddress localAddress() {
            return address;
        }

        @Override
        public X509Certificate[] peerCertificateChain() {
            return new X509Certificate[0];
        }

        @Override
        public String absoluteURI() {
            return "local:" + uri;
        }

        @Override
        public boolean isExpectMultipart() {
            return false;
        }

        @Override
        public HttpConnection connection() {
            return connection;
        }

        @Override
        public HttpServerRequest endHandler(Handler<Void> handler) {
            setEndHandler(handler);
            return this;
        }

        @Override
        public HttpServerRequest handler(Handler<Buffer> handler) {
            setDataHandler(handler);
            // As soon as the dataHandler is set, we can dump the queue in it.
            pump();
            return this;
        }

        @Override
        public HttpServerRequest pause() {
            log.warn("Happy OOM. pause not implemented. {}", getClass().getName());
            return this;
        }

        @Override
        public HttpServerRequest resume() {
            log.debug("resume not implemented: {}", getClass().getName());
            return this;
        }

        @Override
        public HttpServerResponse response() {
            return serverResponse;
        }

        @Override
        public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
            if (log.isDebugEnabled()) {
                log.debug("Happy timeout. As this will ignore your 'exceptionHandler' anyway.", new Exception("stack"));
            }
            return this;
        }

        @Override
        public boolean isEnded() {
            if (log.isDebugEnabled()) {
                log.debug("isEnded() may lie to you. {}", getClass().getName(), new Exception("stack"));
            }
            return false;
        }

        @Override
        public Future<Buffer> body() {
            Promise<Buffer> promise = Promise.promise();
            setBodyHandler(promise::complete);
            return promise.future();
        }

        private final String msg = "TODO: Not impl yet (code_c2398hzuq2j0opij4i)";
        @Override public Context context() { throw new UnsupportedOperationException(msg); }
        @Override public Object metric() { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable String scheme() { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable String host() { throw new UnsupportedOperationException(msg); }
        @Override public long bytesRead() { throw new UnsupportedOperationException(msg); }
        @Override public HttpServerRequest uploadHandler(@Nullable Handler<HttpServerFileUpload> uploadHandler) { throw new UnsupportedOperationException(msg); }
        @Override public MultiMap formAttributes() { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable String getFormAttribute(String attributeName) { throw new UnsupportedOperationException(msg); }
        @Override public Future<ServerWebSocket> toWebSocket() { throw new UnsupportedOperationException(msg); }
        @Override public HttpServerRequest streamPriorityHandler(Handler<StreamPriority> handler) { throw new UnsupportedOperationException(msg); }
        @Override public DecoderResult decoderResult() { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable Cookie getCookie(String name) { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable Cookie getCookie(String name, String domain, String path) { throw new UnsupportedOperationException(msg); }
        @Override public Set<Cookie> cookies(String name) { throw new UnsupportedOperationException(msg); }
        @Override public Set<Cookie> cookies() { throw new UnsupportedOperationException(msg); }
        @Override public HttpServerRequest fetch(long amount) { throw new UnsupportedOperationException(msg); }
        @Override public HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) { throw new UnsupportedOperationException(msg); }
        @Override public Future<Void> end() { throw new UnsupportedOperationException(msg); }
        @Override public Future<NetSocket> toNetSocket() { throw new UnsupportedOperationException(msg); }
        @Override public HttpServerRequest setExpectMultipart(boolean expect) { throw new UnsupportedOperationException(msg); }
        @Override public SSLSession sslSession() { throw new UnsupportedOperationException(msg); }
    };

    private final RoutingContext routingContext = new RoutingContext() {
        @Override
        public HttpServerRequest request() {
            return serverRequest;
        }

        @Override
        public HttpServerResponse response() {
            return serverResponse;
        }

        private static final String msg = "TODO not impl yet (code_q2958zhgu98hu230hj)";
        @Override public void next() { throw new UnsupportedOperationException(msg); }
        @Override public void fail(int statusCode) { throw new UnsupportedOperationException(msg); }
        @Override public void fail(Throwable ex) { throw new UnsupportedOperationException(msg, ex); }
        @Override public void fail(int i, Throwable ex) { throw new UnsupportedOperationException(msg, ex); }
        @Override public RoutingContext put(String key, Object obj) { throw new UnsupportedOperationException(msg); }
        @Override public <T> T get(String key) { throw new UnsupportedOperationException(msg); }
        @Override public <T> T get(String s, T t) { throw new UnsupportedOperationException(msg); }
        @Override public <T> T remove(String key) { throw new UnsupportedOperationException(msg); }
        @Override public Map<String, Object> data() { throw new UnsupportedOperationException(msg); }
        @Override public Vertx vertx() { throw new UnsupportedOperationException(msg); }
        @Override public String mountPoint() { throw new UnsupportedOperationException(msg); }
        @Override public Route currentRoute() { throw new UnsupportedOperationException(msg); }
        @Override public String normalisedPath() { throw new UnsupportedOperationException(msg); }
        @Override public String normalizedPath() { throw new UnsupportedOperationException(msg); }
        @Override public Cookie getCookie(String name) { throw new UnsupportedOperationException(msg); }
        @Override public RoutingContext addCookie(Cookie cookie) { throw new UnsupportedOperationException(msg); }
        @Override public Cookie removeCookie(String name) { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable Cookie removeCookie(String s, boolean b) { throw new UnsupportedOperationException(msg); }
        @Override public int cookieCount() { throw new UnsupportedOperationException(msg); }
        @Override public Map<String, Cookie> cookieMap() { throw new UnsupportedOperationException(msg); }
        @Override public String getBodyAsString() { throw new UnsupportedOperationException(msg); }
        @Override public String getBodyAsString(String encoding) { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable JsonObject getBodyAsJson(int i) { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable JsonArray getBodyAsJsonArray(int i) { throw new UnsupportedOperationException(msg); }
        @Override public JsonObject getBodyAsJson() { throw new UnsupportedOperationException(msg); }
        @Override public JsonArray getBodyAsJsonArray() { throw new UnsupportedOperationException(msg); }
        @Override public Buffer getBody() { throw new UnsupportedOperationException(msg); }
        @Override public Set<FileUpload> fileUploads() { throw new UnsupportedOperationException(msg); }
        @Override public Session session() { throw new UnsupportedOperationException(msg); }
        @Override public boolean isSessionAccessed() { throw new UnsupportedOperationException(msg); }
        @Override public User user() { throw new UnsupportedOperationException(msg); }
        @Override public Throwable failure() { throw new UnsupportedOperationException(msg); }
        @Override public int statusCode() { throw new UnsupportedOperationException(msg); }
        @Override public String getAcceptableContentType() { throw new UnsupportedOperationException(msg); }
        @Override public ParsedHeaderValues parsedHeaders() { throw new UnsupportedOperationException(msg); }
        @Override public int addHeadersEndHandler(Handler<Void> handler) { throw new UnsupportedOperationException(msg); }
        @Override public boolean removeHeadersEndHandler(int handlerID) { throw new UnsupportedOperationException(msg); }
        @Override public int addBodyEndHandler(Handler<Void> handler) { throw new UnsupportedOperationException(msg); }
        @Override public boolean removeBodyEndHandler(int handlerID) { throw new UnsupportedOperationException(msg); }
        @Override public int addEndHandler(Handler<AsyncResult<Void>> handler) { throw new UnsupportedOperationException(msg); }
        @Override public boolean removeEndHandler(int i) { throw new UnsupportedOperationException(msg); }
        @Override public boolean failed() { throw new UnsupportedOperationException(msg); }
        @Override public void setBody(Buffer body) { throw new UnsupportedOperationException(msg); }
        @Override public void setSession(Session session) { throw new UnsupportedOperationException(msg); }
        @Override public void setUser(User user) { throw new UnsupportedOperationException(msg); }
        @Override public void clearUser() { throw new UnsupportedOperationException(msg); }
        @Override public void setAcceptableContentType(String contentType) { throw new UnsupportedOperationException(msg); }
        @Override public void reroute(String path) { throw new UnsupportedOperationException(msg); }
        @Override public void reroute(HttpMethod method, String path) { throw new UnsupportedOperationException(msg); }
        @Override public List<LanguageHeader> acceptableLanguages() { throw new UnsupportedOperationException(msg); }
        @Override public LanguageHeader preferredLanguage() { throw new UnsupportedOperationException(msg); }
        @Override public Map<String, String> pathParams() { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable String pathParam(String name) { throw new UnsupportedOperationException(msg); }
        @Override public MultiMap queryParams() { throw new UnsupportedOperationException(msg); }
        @Override public MultiMap queryParams(Charset charset) { throw new UnsupportedOperationException(msg); }
        @Override public @Nullable List<String> queryParam(String query) { throw new UnsupportedOperationException(msg); }
    };

    public LocalHttpClientRequest(HttpMethod method, String uri, Vertx vertx, Handler<RoutingContext> routingContextHandler, LocalHttpServerResponse response) {
        super(vertx);
        this.vertx = vertx;
        this.method = method;
        this.uri = uri;
        this.routingContextHandler = routingContextHandler;
        this.serverResponse = response;
        this.connection = new LocalHttpConnection(this);
    }

    @Override
    public HttpClientRequest setChunked(boolean chunked) {
        return this;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public String getRawMethod() {
        return method.name();
    }

    @Override
    public HttpClientRequest setRawMethod(String method) {
        return this;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public HttpClientRequest setHost(String host) {
        return this;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public HttpClientRequest putHeader(String name, String value) {
        headers().set(name, value);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, CharSequence value) {
        headers().set(name, value);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(String name, Iterable<String> values) {
        for (String value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, Iterable<CharSequence> values) {
        for (CharSequence value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpClientRequest response(Handler<AsyncResult<HttpClientResponse>> handler) {
        serverResponse.setHttpClientResponseHandler(handler);
        return this;
    }

    @Override
    public Future<HttpClientResponse> response() {
        return Future.succeededFuture(serverResponse.clientResponse);
    }


    private void ensureBound() {
        if (!bound) {
            bound = true;
            routingContextHandler.handle(routingContext);
        }
    }

    @Override
    public Future<Void> write(String chunk) {
        return write(Buffer.buffer(chunk));
    }

    @Override
    public Future<Void> write(String chunk, String enc) {
        return write(Buffer.buffer(chunk, enc));
    }

    @Override
    public Future<Void> write(Buffer data) {
        ensureBound();
        Promise<Void> p = Promise.promise();
        doWrite(data, ex -> {
            if (ex != null) p.fail(ex);
            else p.complete();
        });
        return p.future();
    }

    @Override
    public Future<Void> end(String chunk) {
        return write(chunk).compose(nothing -> end());
    }

    @Override
    public Future<Void> end(String chunk, String enc) {
        return write(chunk, enc).compose(nothing -> end());
    }

    @Override
    public Future<Void> end(Buffer chunk) {
        return write(chunk).compose(nothing -> end());
    }

    @Override
    public Future<Void> end() {
        ensureBound();
        return doEnd();
    }

    @Override
    public HttpClientRequest setTimeout(long timeoutMs) {
        log.debug("Happy debugging. This method just ignores you: {}.setTimeout({})", getClass().getName(), timeoutMs);
        return this;
    }

    @Override
    public HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) {
        log.debug("Happy debugging. This method just ignores you: {}.pushHandler()", getClass().getName());
        return this;
    }

    @Override
    public HttpConnection connection() { return connection; }

    @Override
    public HttpClientRequest connectionHandler(@Nullable Handler<HttpConnection> handler) {
        log.debug("Happy debugging. This method just ignores you: {}.connectionHandler()", getClass().getName());
        return this;
    }

    @Override
    public HttpClientRequest writeCustomFrame(int type, int flags, Buffer payload) {
        log.debug("Happy debugging. This method just ignores you: {}.writeCustomFrame()", getClass().getName());
        return this;
    }

    @Override
    public HttpClientRequest setWriteQueueMaxSize(int maxSize) {
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        log.debug("This method may lie to you: {}.writeQueueFull()", getClass().getName());
        return false;
    }

    @Override
    public HttpClientRequest drainHandler(Handler<Void> handler) {
        if( log.isDebugEnabled() ){
            log.debug("Happy debugging. This method just ignores you: {}.drainHandler()",
                    getClass().getName(), new Exception("find the caller"));
        }
        return this;
    }

    @Override
    public HttpClientRequest exceptionHandler(Handler<Throwable> handler) {
        setExceptionHandler(handler);
        return this;
    }

    public HttpClientRequest setMethod(HttpMethod method) {
        this.method = method;
        return this;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    void onCloseConnectionRequest(Consumer<Throwable> onComplete) {
        // MUST NOT close 'connection' here, as it is the one that called us
        try{
            var tmp = serverResponse;
            headers = null;
            routingContextHandler = null;
            serverResponse = null;
            if( tmp != null ) tmp.close();
        }catch( RuntimeException ex ){
            if( onComplete != null ) vertx.runOnContext(v -> onComplete.accept(ex));
            else throw ex;
        }
        if( onComplete != null ) vertx.runOnContext(v -> onComplete.accept(null));
    }

    private static final String msg = "TODO Not impl (code_98waghiuahguae)";
    @Override public String absoluteURI() { throw new UnsupportedOperationException(msg); }
    @Override public String getURI() { throw new UnsupportedOperationException(msg); }
    @Override public HttpClientRequest setURI(String uri) { throw new UnsupportedOperationException(msg); }
    @Override public String path() { throw new UnsupportedOperationException(msg); }
    @Override public String query() { throw new UnsupportedOperationException(msg); }
    @Override public String getHost() { throw new UnsupportedOperationException(msg); }
    @Override public HttpClientRequest setPort(int port) { throw new UnsupportedOperationException(msg); }
    @Override public int getPort() { throw new UnsupportedOperationException(msg); }
    @Override public HttpClientRequest setMaxRedirects(int maxRedirects) { throw new UnsupportedOperationException(msg); }
    @Override public HttpVersion version() { throw new UnsupportedOperationException(msg); }
    @Override public Future<Void> sendHead() { throw new UnsupportedOperationException(msg); }
    @Override public HttpClientRequest sendHead(Handler<AsyncResult<Void>> completionHandler) { throw new UnsupportedOperationException(msg); }
    @Override public void connect(Handler<AsyncResult<HttpClientResponse>> handler) { throw new UnsupportedOperationException(msg); }
    @Override public Future<HttpClientResponse> connect() { throw new UnsupportedOperationException(msg); }
    @Override public void write(Buffer data, Handler<AsyncResult<Void>> handler) { throw new UnsupportedOperationException(msg); }
    @Override public void write(String chunk, Handler<AsyncResult<Void>> handler) { throw new UnsupportedOperationException(msg); }
    @Override public void write(String chunk, String enc, Handler<AsyncResult<Void>> handler) { throw new UnsupportedOperationException(msg); }
    @Override public boolean reset() { throw new UnsupportedOperationException(msg); }
    @Override public boolean reset(long code) { throw new UnsupportedOperationException(msg); }
    @Override public boolean reset(long code, Throwable cause) { throw new UnsupportedOperationException(msg); }
}
