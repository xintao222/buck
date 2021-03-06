/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import static com.facebook.buck.util.concurrent.MoreExecutors.newMultiThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.event.TraceEventLogger;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidManifestReader;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultAndroidManifestReader;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.TriState;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Helper for executing commands over ADB, especially for multiple devices.
 */
public class AdbHelper {

  private static final long ADB_CONNECT_TIMEOUT_MS = 5000;
  private static final long ADB_CONNECT_TIME_STEP_MS = ADB_CONNECT_TIMEOUT_MS / 10;

  // Taken from ddms source code.
  public static final long INSTALL_TIMEOUT = 2 * 60 * 1000; // 2 min
  public static final long GETPROP_TIMEOUT = 2 * 1000; // 2 seconds

  private final AdbOptions options;
  private final TargetDeviceOptions deviceOptions;
  @Nullable private final ExecutionContext context;
  private final Console console;
  private final BuckEventBus buckEventBus;
  private final BuckConfig buckConfig;

  public AdbHelper(
      AdbOptions adbOptions,
      TargetDeviceOptions deviceOptions,
      ExecutionContext context,
      Console console,
      BuckEventBus buckEventBus,
      BuckConfig buckConfig) {
    this.options = Preconditions.checkNotNull(adbOptions);
    this.deviceOptions = Preconditions.checkNotNull(deviceOptions);
    this.context = context;
    this.console = Preconditions.checkNotNull(console);
    this.buckEventBus = Preconditions.checkNotNull(buckEventBus);
    this.buckConfig = Preconditions.checkNotNull(buckConfig);
  }

  private BuckEventBus getBuckEventBus() {
    return buckEventBus;
  }

  /**
   * Returns list of devices that pass the filter. If there is an invalid combination or no
   * devices are left after filtering this function prints an error and returns null.
   */
  @Nullable
  @VisibleForTesting
  List<IDevice> filterDevices(IDevice[] allDevices) {
    if (allDevices.length == 0) {
      console.printBuildFailure("No devices are found.");
      return null;
    }

    List<IDevice> devices = Lists.newArrayList();
    TriState emulatorsOnly = TriState.UNSPECIFIED;
    if (deviceOptions.isEmulatorsOnlyModeEnabled() && options.isMultiInstallModeEnabled()) {
      emulatorsOnly = TriState.UNSPECIFIED;
    } else if (deviceOptions.isEmulatorsOnlyModeEnabled()) {
      emulatorsOnly = TriState.TRUE;
    } else if (deviceOptions.isRealDevicesOnlyModeEnabled()) {
      emulatorsOnly = TriState.FALSE;
    }

    int onlineDevices = 0;
    for (IDevice device : allDevices) {
      boolean passed = false;
      if (device.isOnline()) {
        onlineDevices++;

        boolean serialMatches = true;
        if (deviceOptions.hasSerialNumber()) {
          serialMatches = device.getSerialNumber().equals(deviceOptions.getSerialNumber());
        }

        boolean deviceTypeMatches;
        if (emulatorsOnly.isSet()) {
          // Only devices of specific type are accepted:
          // either real devices only or emulators only.
          deviceTypeMatches = (emulatorsOnly.asBoolean() == device.isEmulator());
        } else {
          // All online devices match.
          deviceTypeMatches = true;
        }
        passed = serialMatches && deviceTypeMatches;
      }

      if (passed) {
        devices.add(device);
      }
    }

    // Filtered out all devices.
    if (onlineDevices == 0) {
      console.printBuildFailure("No devices are found.");
      return null;
    }

    if (devices.isEmpty()) {
      console.printBuildFailure(String.format(
          "Found %d connected device(s), but none of them matches specified filter.", onlineDevices
      ));
      return null;
    }

    // Found multiple devices but multi-install mode is not enabled.
    if (!options.isMultiInstallModeEnabled() && devices.size() > 1) {
      console.printBuildFailure(
          String.format("%d device(s) matches specified device filter (1 expected).\n" +
                        "Either disconnect other devices or enable multi-install mode (%s).",
                         devices.size(), AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG));
      return null;
    }

    // Report if multiple devices are matching the filter.
    if (devices.size() > 1) {
      console.getStdOut().printf("Found " + devices.size() + " matching devices.\n");
    }
    return devices;
  }

  private boolean isAdbInitialized(AndroidDebugBridge adb) {
    return adb.isConnected() && adb.hasInitialDeviceList();
  }

  /**
   * Creates connection to adb and waits for this connection to be initialized
   * and receive initial list of devices.
   */
  @Nullable
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private AndroidDebugBridge createAdb(ExecutionContext context) {
    try {
      AndroidDebugBridge.init(/* clientSupport */ false);
    } catch (IllegalStateException ex) {
      // ADB was already initialized, we're fine, so just ignore.
    }

    AndroidDebugBridge adb = null;
    if (context != null) {
      adb = AndroidDebugBridge.createBridge(context.getPathToAdbExecutable(), false);
    } else {
      adb = AndroidDebugBridge.createBridge();
    }
    if (adb == null) {
      console.printBuildFailure("Failed to connect to adb. Make sure adb server is running.");
      return null;
    }

    long start = System.currentTimeMillis();
    while (!isAdbInitialized(adb)) {
      long timeLeft = start + ADB_CONNECT_TIMEOUT_MS - System.currentTimeMillis();
      if (timeLeft <= 0) {
        break;
      }
      try {
        Thread.sleep(ADB_CONNECT_TIME_STEP_MS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return isAdbInitialized(adb) ? adb : null;
  }

  /**
   * Execute an {@link AdbCallable} for all matching devices. This functions performs device
   * filtering based on three possible arguments:
   *
   *  -e (emulator-only) - only emulators are passing the filter
   *  -d (device-only) - only real devices are passing the filter
   *  -s (serial) - only device/emulator with specific serial number are passing the filter
   *
   *  If more than one device matches the filter this function will fail unless multi-install
   *  mode is enabled (-x). This flag is used as a marker that user understands that multiple
   *  devices will be used to install the apk if needed.
   */
  public boolean adbCall(AdbCallable adbCallable) {
    List<IDevice> devices;

    try (TraceEventLogger ignored = TraceEventLogger.start(buckEventBus, "set_up_adb_call")) {

      // Initialize adb connection.
      AndroidDebugBridge adb = createAdb(context);
      if (adb == null) {
        console.printBuildFailure("Failed to create adb connection.");
        return false;
      }

      // Build list of matching devices.
      devices = filterDevices(adb.getDevices());
      if (devices == null) {
        if (buckConfig.getRestartAdbOnFailure()) {
          console.printErrorText("No devices found with adb, restarting adb-server.");
          adb.restart();
          devices = filterDevices(adb.getDevices());
        }

        if (devices == null) {
            return false;
        }
      }
    }

    int adbThreadCount = options.getAdbThreadCount();
    if (adbThreadCount <= 0) {
      adbThreadCount = devices.size();
    }

    // Start executions on all matching devices.
    List<ListenableFuture<Boolean>> futures = Lists.newArrayList();
    ListeningExecutorService executorService =
        listeningDecorator(newMultiThreadExecutor(getClass().getSimpleName(), adbThreadCount));
    for (final IDevice device : devices) {
      futures.add(executorService.submit(adbCallable.forDevice(device)));
    }

    // Wait for all executions to complete or fail.
    List<Boolean> results = null;
    try {
      results = Futures.allAsList(futures).get();
    } catch (ExecutionException ex) {
      console.printBuildFailure("Failed: " + adbCallable);
      ex.printStackTrace(console.getStdErr());
      return false;
    } catch (InterruptedException ex) {
      console.printBuildFailure("Interrupted.");
      ex.printStackTrace(console.getStdErr());
      return false;
    } finally {
      executorService.shutdownNow();
    }

    int successCount = 0;
    for (Boolean result : results) {
      if (result) {
        successCount++;
      }
    }
    int failureCount = results.size() - successCount;

    // Report results.
    if (successCount > 0) {
      console.printSuccess(
          String.format("Successfully ran %s on %d device(s)", adbCallable, successCount));
    }
    if (failureCount > 0) {
      console.printBuildFailure(
          String.format("Failed to %s on %d device(s).", adbCallable, failureCount));
    }

    return failureCount == 0;
  }

  /**
   * Base class for commands to be run against an {@link com.android.ddmlib.IDevice IDevice}.
   */
  public abstract static class AdbCallable {

    /**
     * Perform the actions specified by this {@code AdbCallable} and return true on success.
     * @param device the {@link com.android.ddmlib.IDevice IDevice} to run against
     * @return {@code true} if the command succeeded.
     */
    public abstract boolean call(IDevice device) throws Exception;

    /**
     * Wraps this as a {@link java.util.concurrent.Callable Callable&lt;Boolean&gt;} whose
     * {@link Callable#call() call()} method calls
     * {@link AdbHelper.AdbCallable#call(IDevice) call(IDevice)} against the specified
     * device.
     * @param device the {@link com.android.ddmlib.IDevice IDevice} to run against.
     * @return a {@code Callable}
     */
    public Callable<Boolean> forDevice(final IDevice device) {
      return new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          return AdbCallable.this.call(device);
        }
        @Override
        public String toString() {
          return AdbCallable.this.toString();
        }
      };
    }
  }

  /**
   * Implementation of {@link com.android.ddmlib.IShellOutputReceiver} with helper functions to
   * parse output lines and figure out if a call to
   * {@link com.android.ddmlib.IDevice#executeShellCommand(String,
   * com.android.ddmlib.IShellOutputReceiver)} succeeded.
   */
  private abstract static class ErrorParsingReceiver extends MultiLineReceiver {

    private String errorMessage = null;

    /**
     * Look for an error message in {@code line}.
     * @param line
     * @return an error message if {@code line} is indicative of an error, {@code null} otherwise.
     */
    protected abstract String matchForError(String line);

    /**
     * Look for a message indicating success - the error message is reset if this returns
     * {@code true}.
     * @param line
     * @return {@code true} if this line indicates success.
     */
    protected boolean matchForSuccess(String line) {
      return false;
    }

    @Override
    public void processNewLines(String[] lines) {
        for (String line : lines) {
            if (line.length() > 0) {
                if (matchForSuccess(line)) {
                    errorMessage = null;
                } else {
                    String err = matchForError(line);
                    if (err != null) {
                      errorMessage = err;
                    }
                }
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    public String getErrorMessage() {
       return errorMessage;
    }
  }

  /**
   * An exception that indicates that an executed command returned an unsuccessful exit code.
   */
  @SuppressWarnings("serial")
  public static class CommandFailedException extends IOException {
    public final String command;
    public final int exitCode;
    public final String output;
    public CommandFailedException(String command, int exitCode, String output) {
      super("Command '" + command + "' failed with code " + exitCode + ".  Output:\n" + output);
      this.command = command;
      this.exitCode = exitCode;
      this.output = output;
    }
  }

  /**
   * Runs a command on a device and throws an exception if it fails.
   *
   * <p>This will not work if your command contains "exit" or "trap" statements.
   *
   * @param device Device to run the command on.
   * @param command Shell command to execute.  Must not use "exit" or "trap".
   * @return The full text output of the command.
   * @throws CommandFailedException if the command fails.
   */
  public static String executeCommandWithErrorChecking(IDevice device, String command)
      throws
      TimeoutException,
      AdbCommandRejectedException,
      ShellCommandUnresponsiveException,
      IOException {
    CollectingOutputReceiver receiver = new CollectingOutputReceiver();
    device.executeShellCommand(command + " ; echo -n :$?", receiver);
    String realOutput = checkReceiverOutput(command, receiver);
    return realOutput;
  }

  /**
   * This was made public for one specific call site in ExopackageInstaller.
   * If you're reading this, you probably shouldn't call it.  Pretend this method is private.
   */
  public static String checkReceiverOutput(
      String command,
      CollectingOutputReceiver receiver) throws CommandFailedException {
    String fullOutput = receiver.getOutput();
    int colon = fullOutput.lastIndexOf(':');
    String realOutput = fullOutput.substring(0, colon);
    String exitCodeStr = fullOutput.substring(colon + 1);
    int exitCode = Integer.parseInt(exitCodeStr);
    if (exitCode != 0) {
      throw new CommandFailedException(command, exitCode, realOutput);
    }
    return realOutput;
  }

  /**
   * Install apk on all matching devices. This functions performs device
   * filtering based on three possible arguments:
   *
   *  -e (emulator-only) - only emulators are passing the filter
   *  -d (device-only) - only real devices are passing the filter
   *  -s (serial) - only device/emulator with specific serial number are passing the filter
   *
   *  If more than one device matches the filter this function will fail unless multi-install
   *  mode is enabled (-x). This flag is used as a marker that user understands that multiple
   *  devices will be used to install the apk if needed.
   */
  public boolean installApk(
      InstallableApk installableApk,
      InstallCommandOptions options) {
    getBuckEventBus().post(InstallEvent.started(installableApk.getBuildTarget()));

    final File apk = installableApk.getApkPath().toFile();
    final boolean installViaSd = options.shouldInstallViaSd();
    boolean success = adbCall(
        new AdbHelper.AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            return installApkOnDevice(device, apk, installViaSd);
          }

          @Override
          public String toString() {
            return "install apk";
          }
        });
    getBuckEventBus().post(InstallEvent.finished(installableApk.getBuildTarget(), success));

    return success;
  }

  /**
   * Installs apk on specific device. Reports success or failure to console.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public boolean installApkOnDevice(IDevice device, File apk, boolean installViaSd) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    getBuckEventBus().post(LogEvent.info("Installing apk on %s.", name));
    try {
      String reason = null;
      if (installViaSd) {
        reason = deviceInstallPackageViaSd(device, apk.getAbsolutePath());
      } else {
        reason = device.installPackage(apk.getAbsolutePath(), true);
      }
      if (reason != null) {
        console.printBuildFailure(String.format("Failed to install apk on %s: %s.", name, reason));
        return false;
      }
      return true;
    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to install apk on %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  /**
   * Installs apk on device, copying apk to external storage first.
   */
  private String deviceInstallPackageViaSd(IDevice device, String apk) {
    try {
      // Figure out where the SD card is mounted.
      String externalStorage = deviceGetExternalStorage(device);
      if (externalStorage == null) {
        return "Cannot get external storage location.";
      }
      String remotePackage = String.format("%s/%s.apk", externalStorage, UUID.randomUUID());
      // Copy APK to device
      device.pushFile(apk, remotePackage);
      // Install
      String reason = device.installRemotePackage(remotePackage, true);
      // Delete temporary file
      device.removeRemotePackage(remotePackage);
      return reason;
    } catch (Throwable t) {
      return String.valueOf(t.getMessage());
    }
  }

  /**
   * Retrieves external storage location (SD card) from device.
   */
  private String deviceGetExternalStorage(IDevice device) throws TimeoutException,
      AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
    CollectingOutputReceiver receiver = new CollectingOutputReceiver();
    device.executeShellCommand(
        "echo $EXTERNAL_STORAGE",
        receiver,
        AdbHelper.GETPROP_TIMEOUT,
        TimeUnit.MILLISECONDS);
    String value = receiver.getOutput().trim();
    if (value.isEmpty()) {
      return null;
    }
    return value;
  }

  public int startActivity(
      InstallableApk installableApk,
      String activity) throws IOException {

    // Might need the package name and activities from the AndroidManifest.
    Path pathToManifest = installableApk.getManifestPath();
    AndroidManifestReader reader = DefaultAndroidManifestReader.forPath(pathToManifest);

    if (activity == null) {
      // Get list of activities that show up in the launcher.
      List<String> launcherActivities = reader.getLauncherActivities();

      // Sanity check.
      if (launcherActivities.isEmpty()) {
        console.printBuildFailure("No launchable activities found.");
        return 1;
      } else if (launcherActivities.size() > 1) {
        console.printBuildFailure("Default activity is ambiguous.");
        return 1;
      }

      // Construct a component for the '-n' argument of 'adb shell am start'.
      activity = reader.getPackage() + "/" + launcherActivities.get(0);
    } else if (!activity.contains("/")) {
      // If no package name was provided, assume the one in the manifest.
      activity = reader.getPackage() + "/" + activity;
    }

    final String activityToRun = activity;

    PrintStream stdOut = console.getStdOut();
    stdOut.println(String.format("Starting activity %s...", activityToRun));

    getBuckEventBus().post(StartActivityEvent.started(installableApk.getBuildTarget(),
        activityToRun));
    boolean success = adbCall(
        new AdbHelper.AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            String err = deviceStartActivity(device, activityToRun);
            if (err != null) {
              console.printBuildFailure(err);
              return false;
            } else {
              return true;
            }
          }

          @Override
          public String toString() {
            return "start activity";
          }
        });
    getBuckEventBus().post(StartActivityEvent.finished(installableApk.getBuildTarget(),
        activityToRun,
        success));

    return success ? 0 : 1;

  }

  @VisibleForTesting
  String deviceStartActivity(IDevice device, String activityToRun) {
    try {
      AdbHelper.ErrorParsingReceiver receiver = new AdbHelper.ErrorParsingReceiver() {
        @Override
        protected String matchForError(String line) {
          // Parses output from shell am to determine if activity was started correctly.
          return (Pattern.matches("^([\\w_$.])*(Exception|Error|error).*$", line) ||
              line.contains("am: not found")) ? line : null;
        }
      };
      device.executeShellCommand(
          String.format("am start -n %s", activityToRun),
          receiver,
          AdbHelper.INSTALL_TIMEOUT,
          TimeUnit.MILLISECONDS);
      return receiver.getErrorMessage();
    } catch (Exception e) {
      return e.toString();
    }
  }

  /**
   * Uninstall apk from all matching devices.
   *
   * @see #installApk(com.facebook.buck.rules.InstallableApk, InstallCommandOptions)
   */
  public boolean uninstallApk(
      final String packageName,
      final UninstallCommandOptions.UninstallOptions uninstallOptions) {
    getBuckEventBus().post(UninstallEvent.started(packageName));
    boolean success = adbCall(
        new AdbHelper.AdbCallable() {
      @Override
      public boolean call(IDevice device) throws Exception {
        return uninstallApkFromDevice(device, packageName, uninstallOptions.shouldKeepUserData());
      }

      @Override
      public String toString() {
        return "uninstall apk";
      }
    });
    getBuckEventBus().post(UninstallEvent.finished(packageName, success));
    return success;
  }

  /**
   * Uninstalls apk from specific device. Reports success or failure to console.
   * It's currently here because it's used both by {@link InstallCommand} and
   * {@link UninstallCommand}.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  private boolean uninstallApkFromDevice(IDevice device, String packageName, boolean keepData) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    PrintStream stdOut = console.getStdOut();
    stdOut.printf("Removing apk from %s.\n", name);
    try {
      long start = System.currentTimeMillis();
      String reason = deviceUninstallPackage(device, packageName, keepData);
      long end = System.currentTimeMillis();

      if (reason != null) {
        console.printBuildFailure(
            String.format("Failed to uninstall apk from %s: %s.", name, reason));
        return false;
      }

      long delta = end - start;
      stdOut.printf("Uninstalled apk from %s in %d.%03ds.\n", name, delta / 1000, delta % 1000);
      return true;

    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to uninstall apk from %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  /**
   * Modified version of <a href="http://fburl.com/8840769">Device.uninstallPackage()</a>.
   *
   * @param device an {@link IDevice}
   * @param packageName application package name
   * @param keepData  true if user data is to be kept
   * @return error message or null if successful
   * @throws InstallException
   */
  private String deviceUninstallPackage(IDevice device,
      String packageName,
      boolean keepData) throws InstallException {
    try {
      AdbHelper.ErrorParsingReceiver receiver = new AdbHelper.ErrorParsingReceiver() {
        @Override
        protected String matchForError(String line) {
          return line.toLowerCase().contains("failure") ? line : null;
        }
      };
      device.executeShellCommand(
          "pm uninstall " + (keepData ? "-k " : "") + packageName,
          receiver,
          AdbHelper.INSTALL_TIMEOUT,
          TimeUnit.MILLISECONDS);
      return receiver.getErrorMessage();
    } catch (TimeoutException e) {
      throw new InstallException(e);
    } catch (AdbCommandRejectedException e) {
      throw new InstallException(e);
    } catch (ShellCommandUnresponsiveException e) {
      throw new InstallException(e);
    } catch (IOException e) {
      throw new InstallException(e);
    }
  }

  public static String tryToExtractPackageNameFromManifest(InstallableApk androidBinaryRule) {
    Path pathToManifest = androidBinaryRule.getManifestPath();

    // Note that the file may not exist if AndroidManifest.xml is a generated file
    // and the rule has not been built yet.
    if (!Files.isRegularFile(pathToManifest)) {
      throw new HumanReadableException(
          "Manifest file %s does not exist, so could not extract package name.",
          pathToManifest);
    }

    try {
      return DefaultAndroidManifestReader.forPath(pathToManifest).getPackage();
    } catch (IOException e) {
      throw new HumanReadableException("Could not extract package name from %s", pathToManifest);
    }
  }
}
