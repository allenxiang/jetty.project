//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.ssl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

class ReloadableX509TrustManager extends X509ExtendedTrustManager implements X509TrustManager {
    private static final Logger log = LoggerFactory.getLogger(ReloadableX509TrustManager.class);
    private final SecurityStore trustStore;
    private final TrustManagerFactory tmf;
    private X509TrustManager trustManager;
    private long lastReload = 0L;
    private KeyStore trustKeyStore;

    public ReloadableX509TrustManager(SecurityStore trustStore, TrustManagerFactory tmf) {
        this.trustStore = trustStore;
        this.tmf = tmf;
    }

    public KeyStore getTrustKeyStore() {
        return trustKeyStore;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        reloadTrustManager();
        if (trustManager == null) {
            throw new CertificateException("Trust manager not initialized.");
        }
        trustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (trustManager == null) {
            reloadTrustManager();
        }
        if (trustManager == null) {
            throw new CertificateException("Trust manager not initialized.");
        }
        trustManager.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        reloadTrustManager();
        if (trustManager == null) {
            return new X509Certificate[0];
        }
        return trustManager.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
        reloadTrustManager();
        if (trustManager == null) {
            throw new CertificateException("Trust manager not initialized.");
        }
        ((X509ExtendedTrustManager) trustManager).checkClientTrusted(x509Certificates, s, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
        if (trustManager == null) {
            reloadTrustManager();
        }
        if (trustManager == null) {
            throw new CertificateException("Trust manager not initialized.");
        }
        ((X509ExtendedTrustManager) trustManager).checkServerTrusted(x509Certificates, s, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
        reloadTrustManager();
        if (trustManager == null) {
            throw new CertificateException("Trust manager not initialized.");
        }
        ((X509ExtendedTrustManager) trustManager).checkClientTrusted(x509Certificates, s, sslEngine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
        if (trustManager == null) {
            reloadTrustManager();
        }
        if (trustManager == null) {
            throw new CertificateException("Trust manager not initialized.");
        }
        ((X509ExtendedTrustManager) trustManager).checkServerTrusted(x509Certificates, s, sslEngine);
    }

    private void reloadTrustManager() {
        try {
            if (trustManager == null || trustStore.getLastModified() >= lastReload) {
                trustKeyStore = trustStore.load();
                Enumeration<String> alias = trustKeyStore.aliases();
                log.info("Trust manager reloaded.");
                StringBuilder logMessage = new StringBuilder("List of trusted certs: ");
                if (alias.hasMoreElements()) {
                    logMessage.append(alias.nextElement());
                }
                while (alias.hasMoreElements()) {
                    logMessage.append(", ").append(alias.nextElement());
                }
                log.debug(logMessage.toString());
                tmf.init(trustKeyStore);
                trustManager = null;
                TrustManager[] tms = tmf.getTrustManagers();
                for (int i = 0; i < tms.length; i++) {
                    if (tms[i] instanceof X509TrustManager) {
                        trustManager = (X509TrustManager) tms[i];
                    }
                }
                if (trustManager == null) {
                    throw new NoSuchAlgorithmException("No X509TrustManager in TrustManagerFactory");
                }
                lastReload = System.currentTimeMillis();
                // getLastModified() returns timestamp rounded to 1000 ms. Do the same here for lastReload.
                lastReload -= lastReload % 1000;
            }
        } catch (GeneralSecurityException gsEx) {
            log.error("Failed to reload trust manager due to security exception. {}", gsEx.getMessage());
        } catch (IOException ioEx) {
            log.error("Failed to reload trust manager due to IO exception. {}", ioEx.getMessage());
        }
    }
}