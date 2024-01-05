package org.swisspush.gateleen.logging;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Wraps a WriteStream and feeds trsnferred Buffers into a LoggingHandler - interpreted either as
 * request or response body content
 */
public class LoggingWriteStream implements WriteStream<Buffer> {

    private static final Logger log = getLogger(LoggingWriteStream.class);
    private final WriteStream<Buffer> wrappedWriteStream;
    private final LoggingHandler loggingHandler;
    private final boolean isRequest;

    /**
     * @param wrappedWriteStream the WriteStream to be wrapped. Transferred Buffers will be logged
     * @param loggingHandler     Log the transferred Buffers to this LoggingHandler
     * @param isRequest          <b>true</b>: feed the Buffers into LoggingHandler.appendRequestPayload()
     *                           <b>false</b>: feed them into LoggingHandler.appendResponsePayload()
     */
    public LoggingWriteStream(WriteStream<Buffer> wrappedWriteStream, LoggingHandler loggingHandler, boolean isRequest) {
        this.wrappedWriteStream = wrappedWriteStream;
        this.loggingHandler = loggingHandler;
        this.isRequest = isRequest;
    }

    @Override
    public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        wrappedWriteStream.exceptionHandler(handler);
        return this;
    }

    @Override
    public Future<Void> write(Buffer data) {
        Promise<Void> promise = Promise.promise();
        write(data, event -> {
            if( event.failed() ) promise.fail(new Exception(null, event.cause()));
            else promise.complete();
        });
        return promise.future();
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        wrappedWriteStream.write(data);
        if (isRequest) {
            loggingHandler.appendRequestPayload(data);
        } else {
            loggingHandler.appendResponsePayload(data);
        }
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        wrappedWriteStream.end();
    }

    @Override
    public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        wrappedWriteStream.setWriteQueueMaxSize(maxSize);
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        return wrappedWriteStream.writeQueueFull();
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        wrappedWriteStream.drainHandler(handler);
        return this;
    }
}

