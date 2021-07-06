package org.swisspush.gateleen.routing;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import static java.nio.charset.StandardCharsets.ISO_8859_1;


public class RuleUpdateTest {

    private static final Logger logger = LoggerFactory.getLogger(RuleUpdateTest.class);
    private static final String host = "localhost";
    private static final int port = 7012;
    private static final String baseURI = "http://" + host;
    private static final String routingRulesPath = "/playground/server/admin/v1/routing/rules";
    private static final int largeResourceSeed = 42 * 42;
    private static final String largeResourcePath = "/playground/server/test/" + RuleUpdateTest.class.getSimpleName() + "/my-large-resource.bin";
    private static final int largeResourceSize = 16 * 1024 * 1024; // <- Must be larger than all network buffers together.
    private static final String largeResourceContentType = "application/javascript";
    private final int gateleenGracePeriod;

    public RuleUpdateTest() {
        try {
            Field gracePeriodField = Router.class.getDeclaredField("GRACE_PERIOD");
            gracePeriodField.setAccessible(true);
            gateleenGracePeriod = (int) gracePeriodField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void config() {
        RestAssured.baseURI = baseURI;
        RestAssured.port = port;
        System.out.println("Testing against: " + RestAssured.baseURI + ":" + RestAssured.port);
    }

    @Test
    public void gateleenMustProperlyCloseItsDownstreamResponse() throws ExecutionException, InterruptedException, IOException {
        RestAssured.basePath = "/";
        logger.info( "Setup a large resource which we can download during the test." );
        customPut(largeResourcePath, largeResourceContentType,
                newLargeResource(largeResourceSeed, largeResourceSize));

        // Initiate a GET request to our large-resource.
        final InputStream body = newLazyResponseStream(largeResourcePath, gateleenGracePeriod + 12_000);

        // The response stream now has started (but we don't consume anything yet). So
        // now we trigger our rules update (which will corrupt our stream).
        logger.info("Trigger routing rules udpate");
        triggerRoutingRulesUpdate();

        // There's a timeout configured in gateleen. If we consume the stream too fast,
        // our problem does not occur. So we have to wait until gateleen actually
        // performs his "rule update". Or to be more accurate blindly closes the old connections.
        logger.info("Wait for gateleen to close his old clients (takes a while ...).");
        Thread.sleep(gateleenGracePeriod + 1000); // Just a tiny bit more.

        // Now gateleen should already have closed our response. We now can read all that stuff
        // which already was on-the-wire before gateleen sent the RST.
        logger.info("Start reading the response.");
        // This 'read()' call here will run into a timeout because gateleen does not
        // deliver any further data on that stream (no matter how long we wait).
        {
            long bytesSoFar = 0;
            byte[] buf = new byte[128 * 1024];
            while (true) {
                int len = body.read(buf);
                if (len == -1) {
                    break; // EOF
                }
                bytesSoFar += len;
                logger.debug(String.format("Read %9d of %9d bytes (%3d%%)", bytesSoFar, largeResourceSize, bytesSoFar*100 / largeResourceSize));
            }
            logger.info("EOF reached on response. Did read {} bytes.", bytesSoFar);
        }
    }

    @Test
    public void errorInStreamMustBeRecognizableOnClient() throws IOException, InterruptedException {
        RestAssured.basePath = "/";
        logger.info( "Setup a large resource which we can download during the test." );
        RestAssured.given().header("Content-Type", "application/octet-stream").body(newLargeResource(largeResourceSeed, largeResourceSize)).put(largeResourcePath);

        // Initiate a GET request to our large-resource.
        final InputStream body = newLazyResponseStream(largeResourcePath, gateleenGracePeriod + 12_000);

        // The response stream now has started (but we don't consume anything yet). So
        // now we trigger our rules update (which will corrupt our stream).
        logger.info("Trigger routing rules udpate");
        triggerRoutingRulesUpdate();

        // There's a timeout configured in gateleen. If we consume the stream too fast,
        // our problem does not occur. So we have to wait until gateleen actually
        // performs his "rule update". Or to be more accurate blindly closes the old connections.
        logger.info("Wait for gateleen to close his old clients (takes a while ...).");
        Thread.sleep(gateleenGracePeriod + 1000); // Just a tiny bit more.

        // Now gateleen should already have closed our response. We now can read all that stuff
        // which already was on-the-wire before gateleen sent the RST.
        logger.info("Start reading the response.");
        // This 'read()' call here will run into a timeout because gateleen does not
        // deliver any further data on that stream (no matter how long we wait).
        int bytesSoFar = 0;
        {
            // Open the same stream as we expect to download. So we can validate what we download.
            InputStream referenceSrc = newLargeResource(largeResourceSeed, largeResourceSize);
            byte[] dload = new byte[128 * 1024];
            while (true) {
                int readLen = body.read(dload);
                if (readLen == -1) {
                    break; // EOF
                }
                bytesSoFar += readLen;
                // Validate that downloaded stream content is correct.
                for (int i = 0; i < readLen; ++i) {
                    int cExp = referenceSrc.read();
                    int cAct = (dload[i] & 0xFF);
                    if (cExp != cAct) { // Only to log some debugging info.
                        int offset = bytesSoFar + i > 5 ? bytesSoFar + i - 5 : 0;
                        int snipLength = Math.min(42, readLen - i); // Print maximally
                        logger.debug("Stream at offset={} length={} is: '{}'",
                                offset, snipLength, new String(dload, offset - bytesSoFar, snipLength, ISO_8859_1));
                    }
                    Assert.assertEquals("Stream must not contain incorrect data", cExp, cAct);
                }
                logger.debug(String.format("Read %9d of %9d bytes (%3d%%)", bytesSoFar, largeResourceSize, bytesSoFar*100 / largeResourceSize));
            }
            logger.info("EOF reached on response.");
        }
        Assert.assertEquals(largeResourceSize, bytesSoFar);
    }

    /**
     * Produces some binary garbage based on the passed seed. Useful to produce
     * large payloads in a reproducible way.
     */
    private static InputStream newLargeResource(int seed, int size) {
        return new InputStream() {
            int producedBytes = 0;
            final Random random = new Random(seed);

            public int read() throws IOException {
                if (producedBytes >= size) {
                    return -1; // EOF
                }
                producedBytes += 1;
                return random.nextInt() & 0x000000FF; // Just another (pseudo) random byte.
            }
        };
    }

    private InputStream newLazyResponseStream(String path, int readTimeoutMs) throws IOException {

        Assert.assertTrue(path.startsWith("/"));

        // Request
        final URL url = new URL(baseURI + ":" + port + path);
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoInput( true );
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty( "Method" , "GET" );
        connection.setRequestProperty( "Accept" , "application/octet-stream" ); // NO! I don't wanna your index.html ...

        // Response
        Assert.assertEquals(200, connection.getResponseCode());
        return connection.getInputStream();
    }

    /**
     * I liked to use RestAssured. But cannot because:
     * - We MUST use Content-Type "application/javascript" (which is a non-existing
     *   type BTW) as we cannot use "application/octet-stream" due to the gateleen
     *   constraint handler.
     * - RestAssured is unable to "encode" binary garbage as "application/javascript".
     *
     * So just wrote the HTTP PUT in pure java. And guess what: Just works.
     */
    private void customPut(String path, String contentType, InputStream body) throws IOException {
        Assert.assertTrue(path.startsWith("/"));
        HttpURLConnection connection = (HttpURLConnection) new URL(baseURI + ":" + port + path).openConnection();
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setChunkedStreamingMode(8192);
        connection.setRequestMethod("PUT");
        connection.addRequestProperty("Content-Type", contentType);
        byte[] buf = new byte[1024];
        OutputStream snk = connection.getOutputStream();
        while (true) {
            int readLen = body.read(buf);
            if (readLen == -1) break; // EOF
            snk.write(buf);
        }
        snk.close();
        Assert.assertEquals(200, connection.getResponseCode());
        // Consume body (which should be empty)
        InputStream rspBody = connection.getInputStream();
        while (rspBody.read() != -1) ;
    }

    private void triggerRoutingRulesUpdate() {
        Response rsp = RestAssured.get(routingRulesPath);
        Assert.assertEquals(200, rsp.statusCode());
        RestAssured.given().header("Content-Type", "application/json").body(rsp.body().print()).put(routingRulesPath);
    }

}
