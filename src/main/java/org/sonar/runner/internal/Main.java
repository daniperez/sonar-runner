/*
 * Sonar Standalone Runner
 * Copyright (C) 2011 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.runner.internal;

import org.sonar.runner.Runner;
import org.sonar.runner.RunnerException;
import org.sonar.runner.internal.bootstrapper.BootstrapException;
import org.sonar.runner.internal.bootstrapper.utils.PrivateIOUtils;
import org.sonar.runner.utils.SonarRunnerVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Arguments :
 * <ul>
 * <li>runner.home: optional path to runner home (root directory with sub-directories bin, lib and conf)</li>
 * <li>runner.settings: optional path to runner global settings, usually ${runner.home}/conf/sonar-runner.properties.
 * This property is used only if ${runner.home} is not defined</li>
 * <li>project.home: path to project root directory. If not set, then it's supposed to be the directory where the runner is executed</li>
 * <li>project.settings: optional path to project settings. Default value is ${project.home}/sonar-project.properties.</li>
 * </ul>
 *
 * @since 1.0
 */
public final class Main {

  private static final String RUNNER_HOME = "runner.home";
  private static final String RUNNER_SETTINGS = "runner.settings";
  private static final String PROJECT_HOME = "project.home";
  private static final String PROJECT_SETTINGS = "project.settings";

  private static boolean debugMode = false;

  /**
   * Entry point of the program.
   */
  public static void main(String[] args) {
    long startTime = System.currentTimeMillis();
    try {
      Properties props = loadProperties(args);
      Runner runner = Runner.create(props);
      log("Runner version: " + SonarRunnerVersion.getVersion());
      log("Java version: " + System.getProperty("java.version", "<unknown java version>")
        + ", vendor: " + System.getProperty("java.vendor", "<unknown vendor>"));
      log("OS name: \"" + System.getProperty("os.name") + "\", version: \"" + System.getProperty("os.version") + "\", arch: \"" + System.getProperty("os.arch") + "\"");
      if (debugMode) {
        log("Other system properties:");
        log("  - sun.arch.data.model: \"" + System.getProperty("sun.arch.data.model") + "\"");
      }
      log("Server: " + runner.getSonarServerURL());
      try {
        log("Work directory: " + runner.getWorkDir().getCanonicalPath());
      } catch (IOException e) {
        throw new RunnerException(e);
      }
      runner.execute();
    } finally {
      printStats(startTime);
    }
  }

  private static void printStats(long startTime) {
    long time = System.currentTimeMillis() - startTime;
    log("Total time: " + formatTime(time));

    System.gc();
    Runtime r = Runtime.getRuntime();
    long mb = 1024 * 1024;
    log("Final Memory: " + (r.totalMemory() - r.freeMemory()) / mb + "M/" + r.totalMemory() / mb + "M");
  }

  static String formatTime(long time) {
    long h = time / (60 * 60 * 1000);
    long m = (time - h * 60 * 60 * 1000) / (60 * 1000);
    long s = (time - h * 60 * 60 * 1000 - m * 60 * 1000) / 1000;
    long ms = time % 1000;
    final String format;
    if (h > 0) {
      format = "%1$d:%2$02d:%3$02d.%4$03ds";
    } else if (m > 0) {
      format = "%2$d:%3$02d.%4$03ds";
    } else {
      format = "%3$d.%4$03ds";
    }
    return String.format(format, h, m, s, ms);
  }

  static Properties loadProperties(String[] args) {
    Properties commandLineProps = new Properties();
    commandLineProps.putAll(System.getProperties());
    commandLineProps.putAll(parseArguments(args));

    Properties result = new Properties();
    result.putAll(loadRunnerProperties(commandLineProps));
    result.putAll(loadProjectProperties(commandLineProps));
    result.putAll(commandLineProps);

    if (result.containsKey(PROJECT_HOME)) {
      // the real property of the Sonar Runner is "sonar.projectDir"
      String baseDir = result.getProperty(PROJECT_HOME);
      result.remove(PROJECT_HOME);
      result.put(Runner.PROPERTY_SONAR_PROJECT_BASEDIR, baseDir);
    }

    return result;
  }

  static Properties loadRunnerProperties(Properties props) {
    File settingsFile = locatePropertiesFile(props, RUNNER_HOME, "conf/sonar-runner.properties", RUNNER_SETTINGS);
    if (settingsFile != null && settingsFile.isFile() && settingsFile.exists()) {
      log("Runner configuration file: " + settingsFile.getAbsolutePath());
      return toProperties(settingsFile);
    } else {
      log("Runner configuration file: NONE");
    }
    return new Properties();
  }

  static Properties loadProjectProperties(Properties props) {
    File settingsFile = locatePropertiesFile(props, PROJECT_HOME, "sonar-project.properties", PROJECT_SETTINGS);
    if (settingsFile != null && settingsFile.isFile() && settingsFile.exists()) {
      log("Project configuration file: " + settingsFile.getAbsolutePath());
      return toProperties(settingsFile);
    } else {
      log("Project configuration file: NONE");
    }
    return new Properties();
  }

  private static File locatePropertiesFile(Properties props, String homeKey, String relativePathFromHome, String settingsKey) {
    File settingsFile = null;
    String runnerHome = props.getProperty(homeKey);
    if (runnerHome != null && !"".equals(runnerHome)) {
      settingsFile = new File(runnerHome, relativePathFromHome);
    }

    if (settingsFile == null || !settingsFile.exists()) {
      String settingsPath = props.getProperty(settingsKey);
      if (settingsPath != null && !"".equals(settingsPath)) {
        settingsFile = new File(settingsPath);
      }
    }
    return settingsFile;
  }

  private static Properties toProperties(File file) {
    InputStream in = null;
    Properties properties = new Properties();
    try {
      in = new FileInputStream(file);
      properties.load(in);
      return properties;

    } catch (Exception e) {
      throw new BootstrapException(e);

    } finally {
      PrivateIOUtils.closeQuietly(in);
    }
  }

  static Properties parseArguments(String[] args) {
    Properties props = new Properties();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if ("-h".equals(arg) || "--help".equals(arg)) {
        printUsage();

      } else if ("-X".equals(arg) || "--debug".equals(arg)) {
        props.setProperty(Runner.PROPERTY_VERBOSE, "true");
        debugMode = true;

      } else if ("-D".equals(arg) || "--define".equals(arg)) {
        i++;
        if (i >= args.length) {
          printError("Missing argument for option --define");
        }
        arg = args[i];
        parseProperty(arg, props);
      } else if (arg.startsWith("-D")) {
        arg = arg.substring(2);
        parseProperty(arg, props);
      } else {
        printError("Unrecognized option: " + arg);
      }
    }
    return props;
  }

  private static void parseProperty(String arg, Properties props) {
    final String key, value;
    int j = arg.indexOf('=');
    if (j == -1) {
      key = arg;
      value = "true";
    } else {
      key = arg.substring(0, j);
      value = arg.substring(j + 1);
    }
    props.setProperty(key, value);
  }

  private static void printUsage() {
    log("");
    log("usage: sonar-runner [options]");
    log("");
    log("Options:");
    log(" -h,--help             Display help information");
    log(" -X,--debug            Produce execution debug output");
    log(" -D,--define <arg>     Define property");
    System.exit(0); // NOSONAR
  }

  private static void printError(String message) {
    log("");
    log(message);
    printUsage();
  }

  private static void log(String message) {
    System.out.println(message); // NOSONAR
  }

  private Main() {
  }
}