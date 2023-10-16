package com.sourcegraph.cody.localapp;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class LocalAppManager {
  private static final Logger logger = Logger.getInstance(LocalAppManager.class);
  public static final String DEFAULT_LOCAL_APP_URL = "http://localhost:3080/";

  public static boolean isLocalAppInstalled() {
    return true;
  }

  public static boolean isPlatformSupported() {
    return true;
  }

  @NotNull
  public static Optional<String> getLocalAppAccessToken() {
    return Optional.of("");
  }

  public static boolean isLocalAppRunning() {
    return true;
  }

  /**
   * @return gets the local app url from the local app json file, falls back to
   *     [[DEFAULT_LOCAL_APP_URL]] if not present
   */
  @NotNull
  public static String getLocalAppUrl() {
    return DEFAULT_LOCAL_APP_URL;
  }

  public static void runLocalApp() {
  }

  public static void browseLocalAppInstallPage() {
    BrowserUtil.browse("https://about.sourcegraph.com/app");
  }
}
