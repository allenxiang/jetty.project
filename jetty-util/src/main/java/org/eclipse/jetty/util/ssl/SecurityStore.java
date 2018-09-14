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

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Password;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

class SecurityStore {
    private final String type;
    private final Resource resource;
    private final Password password;

    SecurityStore(String type, Resource resource, Password password) {
        this.type = type == null ? KeyStore.getDefaultType() : type;
        this.resource = resource;
        this.password = password;
    }

    Password getPassword() {
        return password;
    }

    KeyStore load() throws GeneralSecurityException, IOException {
        try (InputStream inStream = resource.getInputStream()) {
            KeyStore ks = KeyStore.getInstance(type);
            // If a password is not set access to the truststore is still available, but integrity checking is disabled.
            char[] passwordChars = password != null ? password.toString().toCharArray() : null;
            ks.load(inStream, passwordChars);
            return ks;
        }
    }

    long getLastModified() {
        return resource.lastModified();
    }
} 