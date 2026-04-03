// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
package tests.junittests;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.security.CefCertStatus;
import org.cef.security.CefSSLInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.net.ssl.*;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.util.Date;

@ExtendWith(TestSetupExtension.class)
class SelfSignedSSLTest {
    private static class SSLAcceptTestFrame extends TestFrame {
        volatile CefSSLInfo sslInfo;

        SSLAcceptTestFrame(String url) {
            super(url);
        }

        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            super.onLoadEnd(browser, frame, httpStatusCode);
            terminateTest();
        }

        @Override
        public boolean onCertificateError(CefBrowser browser, ErrorCode cert_error, String request_url, CefSSLInfo sslInfo, CefCallback callback) {
            this.sslInfo = sslInfo;
            callback.Continue();
            return true;
        }
    }

    private static class SSLRejectTestFrame extends TestFrame {
        volatile boolean isOnCertificateErrorCalled = false;
        volatile boolean isOnLoadErrorCalled = false;

        SSLRejectTestFrame(String url) {
            super(url);
        }

        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            super.onLoadEnd(browser, frame, httpStatusCode);
            terminateTest();
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
            isOnLoadErrorCalled = true;
            super.onLoadError(browser, frame, errorCode, errorText, failedUrl);
            terminateTest();
        }

        @Override
        public boolean onCertificateError(CefBrowser browser, ErrorCode cert_error, String request_url, CefSSLInfo sslInfo, CefCallback callback) {
            isOnCertificateErrorCalled = true;
            return false;
        }
    }
    final static String STORAGE_PASSWORD = "password";

    /**
     * Creates a KeyStore with an expired self-signed certificate.
     * The certificate expired in the past, which forces Chromium to
     * trigger onCertificateError with ERR_CERT_DATE_INVALID.
     */
    static KeyStore makeExpiredKeyStore() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();

            // Use sun.security.x509 to create a self-signed cert with past dates
            sun.security.x509.X500Name owner = new sun.security.x509.X500Name(
                    "CN=jcef-test-expired, O=JCEF Test, L=Test, C=DE");
            Date notBefore = new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000); // 1 year ago
            Date notAfter = new Date(System.currentTimeMillis() - 1L * 24 * 60 * 60 * 1000);    // 1 day ago

            sun.security.x509.CertificateValidity validity =
                    new sun.security.x509.CertificateValidity(notBefore, notAfter);

            sun.security.x509.X509CertInfo info = new sun.security.x509.X509CertInfo();
            info.set(sun.security.x509.X509CertInfo.VERSION,
                    new sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3));
            info.set(sun.security.x509.X509CertInfo.SERIAL_NUMBER,
                    new sun.security.x509.CertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis())));
            info.set(sun.security.x509.X509CertInfo.SUBJECT, owner);
            info.set(sun.security.x509.X509CertInfo.ISSUER, owner);
            info.set(sun.security.x509.X509CertInfo.VALIDITY, validity);
            info.set(sun.security.x509.X509CertInfo.KEY,
                    new sun.security.x509.CertificateX509Key(keyPair.getPublic()));
            info.set(sun.security.x509.X509CertInfo.ALGORITHM_ID,
                    new sun.security.x509.CertificateAlgorithmId(
                            sun.security.x509.AlgorithmId.get("SHA256withRSA")));

            sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
            cert.sign(keyPair.getPrivate(), "SHA256withRSA");

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, STORAGE_PASSWORD.toCharArray());
            ks.setKeyEntry("expired", keyPair.getPrivate(), STORAGE_PASSWORD.toCharArray(),
                    new Certificate[]{cert});
            return ks;
        } catch (Exception e) {
            Assertions.fail("Failed to create expired certificate: " + e.getMessage());
            return null;
        }
    }

    static HttpsServer makeHttpsServer(KeyStore keyStore) {
        try {
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");

            // setup the key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, STORAGE_PASSWORD.toCharArray());

            // setup the trust manager factory
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // setup the HTTPS context and parameters
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                public void configure(HttpsParameters params) {
                    try {
                        SSLContext context = getSSLContext();
                        SSLEngine engine = context.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(new String[]{"TLSv1.2"});
                        SSLParameters sslParameters = context.getSupportedSSLParameters();
                        params.setSSLParameters(sslParameters);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
            server.createContext("/test", t -> {
                String response = "This is the response";
                t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                t.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
            });
            return server;
        } catch (Exception e) {
            Assertions.fail("Failed to start HTTPS server. " + e);
        }
        return null;
    }

    @Test
    void certificateAccepted() {
        KeyStore keyStore = makeExpiredKeyStore();
        Certificate[] certificateChainExpected = null;
        try {
            certificateChainExpected = keyStore.getCertificateChain("expired");
        } catch (KeyStoreException e) {
            Assertions.fail("Failed to get certificate chain from the key store");
        }

        HttpsServer server = makeHttpsServer(keyStore);
        server.start();

        SSLAcceptTestFrame myFrame = new SSLAcceptTestFrame(
                "https://localhost:" + server.getAddress().getPort() + "/test");

        myFrame.awaitCompletion();

        Assertions.assertNotNull(myFrame.sslInfo, "onCertificateError was not called");
        Assertions.assertArrayEquals(certificateChainExpected, myFrame.sslInfo.certificate.getCertificatesChain());
        Assertions.assertTrue(CefCertStatus.CERT_STATUS_DATE_INVALID.hasStatus(myFrame.sslInfo.statusBitset)
                || CefCertStatus.CERT_STATUS_AUTHORITY_INVALID.hasStatus(myFrame.sslInfo.statusBitset));
        server.stop(0);
    }

    @Test
    void certificateRejected() {
        KeyStore keyStore = makeExpiredKeyStore();
        HttpsServer server = makeHttpsServer(keyStore);
        server.start();

        SSLRejectTestFrame frame = new SSLRejectTestFrame(
                "https://localhost:" + server.getAddress().getPort() + "/test");

        frame.awaitCompletion();
        Assertions.assertTrue(frame.isOnCertificateErrorCalled);
        Assertions.assertTrue(frame.isOnLoadErrorCalled);

        server.stop(0);
    }
}
