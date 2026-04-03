// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefSettings;
import org.cef.SystemBootstrap;
import org.cef.handler.CefAppHandlerAdapter;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import tests.junittests.setup.NativesInstaller;

import java.io.File;
import java.util.concurrent.CountDownLatch;

// All test cases must install this extension for CEF to be properly initialized
// and shut down.
//
// For example:
//
//   @ExtendWith(TestSetupExtension.class)
//   class FooTest {
//        @Test
//        void testCaseThatRequiresCEF() {}
//   }
//
// This code is based on https://stackoverflow.com/a/51556718.
public class TestSetupExtension
        implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {
    private static boolean initialized_ = false;
    private static CountDownLatch countdown_ = new CountDownLatch(1);

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!initialized_) {
            initialized_ = true;
            initialize(context);
        }
    }

    // Executed before any tests are run.
    private void initialize(ExtensionContext context) {
        TestSetupContext.initialize(context);

        if (TestSetupContext.debugPrint()) {
            System.out.println("TestSetupExtension.initialize");
        }

        // Register a callback hook for when the root test context is shut down.
        context.getRoot().getStore(GLOBAL).put("jcef_test_setup", this);

        // Ensure native binaries are available (download if necessary).
        try {
            final File nativesDir = NativesInstaller.ensureInstalled();

            // Register a custom loader that loads native libraries from the
            // installed directory using absolute paths, bypassing java.library.path.
            SystemBootstrap.setLoader(new SystemBootstrap.Loader() {
                @Override
                public void loadLibrary(String libname) {
                    if ("jawt".equals(libname)) {
                        // jawt is part of the JDK, load normally
                        System.loadLibrary(libname);
                    } else {
                        String fileName = NativesInstaller.mapLibraryName(libname);
                        File libFile = new File(nativesDir, fileName);
                        if (libFile.exists()) {
                            System.load(libFile.getAbsolutePath());
                        } else {
                            // Fallback: try subdirectories (binary_distrib layout)
                            File found = findLibrary(nativesDir, fileName);
                            if (found != null) {
                                System.load(found.getAbsolutePath());
                            } else {
                                // Last resort: system default
                                System.loadLibrary(libname);
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            System.out.println("WARNING: Could not install native binaries: " + e.getMessage());
            System.out.println("Tests will attempt to use java.library.path instead.");
        }

        // Perform startup initialization on platforms that require it.
        if (!CefApp.startup(null)) {
            System.out.println("Startup initialization failed!");
            return;
        }

        CefApp.addAppHandler(new CefAppHandlerAdapter(null) {
            @Override
            public void stateHasChanged(org.cef.CefApp.CefAppState state) {
                if (state == CefAppState.TERMINATED) {
                    // Signal completion of CEF shutdown.
                    countdown_.countDown();
                }
            }
        });

        // Initialize the singleton CefApp instance.
        CefSettings settings = new CefSettings();
        CefApp.getInstance(settings);
    }

    /**
     * Recursively searches for a library file in a directory tree.
     */
    private static File findLibrary(File dir, String fileName) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(fileName)) {
                return f;
            }
        }
        // Search subdirectories
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findLibrary(f, fileName);
                if (found != null) return found;
            }
        }
        return null;
    }

    // Executed after all tests have completed.
    @Override
    public void close() {
        if (TestSetupContext.debugPrint()) {
            System.out.println("TestSetupExtension.close");
        }

        CefApp.getInstance().dispose();

        // Wait for CEF shutdown to complete.
        try {
            countdown_.await();
        } catch (InterruptedException e) {
        }
    }
}
