// Copyright © 2013-2018 Esko Luontola and other Retrolambda contributors
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package net.orfjackal.retrolambda.maven;

import com.google.common.base.*;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import net.orfjackal.retrolambda.api.RetrolambdaApi;
import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.*;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.*;

import java.io.*;
import java.util.*;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

abstract class ProcessClassesMojo extends AbstractMojo {

    private static final Map<String, Integer> targetBytecodeVersions = ImmutableMap.of(
            "1.5", 49,
            "1.6", 50,
            "1.7", 51,
            "1.8", 52
    );

    @Component
    ToolchainManager toolchainManager;

    @Component
    private BuildPluginManager pluginManager;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /**
     * Whether to skip execution of this plugin.
     * 
     * @since 2.5.8
     */
    @Parameter(defaultValue = "false", property = "skip", required = false)
    public boolean skip;

    /**
     * Directory of the Java 8 installation for running Retrolambda.
     * The JRE to be used will be determined in priority order:
     * <ol>
     * <li>This parameter</li>
     * <li><a href="http://maven.apache.org/plugins/maven-toolchains-plugin/toolchains/jdk.html">JDK toolchain</a></li>
     * <li>Same as Maven</li>
     * </ol>
     *
     * @since 1.2.0
     */
    @Parameter(property = "java8home", required = false)
    public File java8home;

    /**
     * The Java version targeted by the bytecode processing. Possible values are
     * 1.5, 1.6, 1.7 and 1.8. After processing the classes will be compatible
     * with the target JVM provided the known limitations are considered. See
     * <a href="https://github.com/luontola/retrolambda">project documentation</a>
     * for more details.
     *
     * @since 1.2.0
     */
    @Parameter(defaultValue = "1.7", property = "retrolambdaTarget", required = true)
    public String target;

    /**
     * Whether to backport default methods and static methods on interfaces.
     * LIMITATIONS: All backported interfaces and all classes which implement
     * them or call their static methods must be backported together,
     * with one execution of Retrolambda.
     *
     * @since 2.0.0
     */
    @Parameter(defaultValue = "false", property = "retrolambdaDefaultMethods", required = true)
    public boolean defaultMethods;

    /**
     * Whether to apply experimental javac issues workarounds.
     *
     * @since 2.5.5
     */
    @Parameter(defaultValue = "false", property = "retrolambdaJavacHacks", required = true)
    public boolean javacHacks;

    /**
     * Reduces the amount of logging.
     *
     * @since 2.4.0
     */
    @Parameter(defaultValue = "false", property = "retrolambdaQuiet", required = true)
    public boolean quiet;

    /**
     * Forces Retrolambda to run in a separate process. The default is not to fork,
     * in which case Maven has to run under Java 8, or this plugin will fall back
     * to forking. The forked process uses a Java agent hook for capturing the lambda
     * classes generated by Java 8, whereas the non-forked version hooks into internal
     * Java APIs, making it more susceptible to breaking between Java releases.
     *
     * @since 1.6.0
     */
    @Parameter(defaultValue = "false")
    public boolean fork;

    /**
     * Whether to replace occurrences of classpath entries ending in {@code /classes}
     * with entries ending in {@code /classes-java8} if such directory is available.
     * @since 2.5.8
     */
    @Parameter(defaultValue = "false", property = "fixJava8Classpath", required = false)
    public boolean fixJava8Classpath;

    protected abstract File getInputDir();

    protected abstract File getOutputDir();

    protected abstract List<String> getClasspathElements() throws DependencyResolutionRequiredException;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping execution (skip=true)");
            return;
        }

        validateTarget();
        validateFork();

        Properties config = new Properties();
        config.setProperty(RetrolambdaApi.BYTECODE_VERSION, "" + targetBytecodeVersions.get(target));
        config.setProperty(RetrolambdaApi.DEFAULT_METHODS, "" + defaultMethods);
        config.setProperty(RetrolambdaApi.QUIET, "" + quiet);
        config.setProperty(RetrolambdaApi.INPUT_DIR, getInputDir().getAbsolutePath());
        config.setProperty(RetrolambdaApi.OUTPUT_DIR, getOutputDir().getAbsolutePath());
        config.setProperty(RetrolambdaApi.CLASSPATH, getClasspath());
        config.setProperty(RetrolambdaApi.JAVAC_HACKS, "" + javacHacks);
        config.setProperty(RetrolambdaApi.FIX_JAVA8_CLASSPATH, "" + fixJava8Classpath);

        if (fork) {
            processClassesInForkedProcess(config);
        } else {
            processClassesInCurrentProcess(config);
        }
    }

    private void validateTarget() throws MojoExecutionException {
        if (!targetBytecodeVersions.containsKey(target)) {
            String possibleValues = Joiner.on(", ").join(new TreeSet<String>(targetBytecodeVersions.keySet()));
            throw new MojoExecutionException(
                    "Unrecognized target '" + target + "'. Possible values are " + possibleValues);
        }
    }

    private void validateFork() {
        if (!fork && !SystemUtils.isJavaVersionAtLeast(1.8f)) {
            getLog().warn("Maven is not running under Java 8 - forced to fork the process");
            fork = true;
        }
    }

    private void processClassesInCurrentProcess(Properties config) throws MojoExecutionException {
        getLog().info("Processing classes with Retrolambda");
        try {
            // XXX: Retrolambda is compiled for Java 8, but this Maven plugin is compiled for Java 6,
            // so we need to break the compile-time dependency using reflection
            Class.forName("net.orfjackal.retrolambda.Retrolambda")
                    .getMethod("run", Properties.class)
                    .invoke(null, config);
        } catch (Throwable t) {
            throw new MojoExecutionException("Failed to run Retrolambda", t);
        }
    }

    private void processClassesInForkedProcess(Properties config) throws MojoExecutionException {
        String version = getRetrolambdaVersion();
        getLog().info("Retrieving Retrolambda " + version);
        try {
          retrieveRetrolambdaJar(version);
        } catch(MojoExecutionException e) {
          if (e.getMessage().contains("has not been packaged yet")) {
            getLog().info(e.getMessage());
            return;
          } else {
            throw e;
          }
        }

        getLog().info("Processing classes with Retrolambda");
        String retrolambdaJar = getRetrolambdaJarPath();
        File classpathFile = getClasspathFile();
        try {
            List<Element> args = new ArrayList<Element>();
            for (Object key : config.keySet()) {
                Object value = config.get(key);
                if (key.equals(RetrolambdaApi.CLASSPATH)) {
                    key = RetrolambdaApi.CLASSPATH_FILE;
                    value = classpathFile.getAbsolutePath();
                }
                args.add(element("arg", attribute("value", "-D" + key + "=" + value)));
            }
            args.add(element("arg", attribute("value", "-javaagent:" + retrolambdaJar)));
            args.add(element("arg", attribute("value", "-jar")));
            args.add(element("arg", attribute("value", retrolambdaJar)));
            executeMojo(
                    plugin(groupId("org.apache.maven.plugins"),
                            artifactId("maven-antrun-plugin"),
                            version("1.7")),
                    goal("run"),
                    configuration(element(
                            "target",
                            element("exec",
                                    attributes(
                                            attribute("executable", getJavaCommand()),
                                            attribute("failonerror", "true")),
                                    args.toArray(new Element[0])))),
                    executionEnvironment(project, session, pluginManager));
        } finally {
            if (!classpathFile.delete()) {
                getLog().warn("Unable to delete " + classpathFile);
            }
        }
    }

    private void retrieveRetrolambdaJar(String version) throws MojoExecutionException {
        // TODO: use Maven's built-in artifact resolving, so that we can refer to retrolambda.jar in the local repository without copying it
        executeMojo(
                plugin(groupId("org.apache.maven.plugins"),
                        artifactId("maven-dependency-plugin"),
                        version("2.8")),
                goal("copy"),
                configuration(element("artifactItems",
                        element("artifactItem",
                                element(name("groupId"), "com.kohlschutter.retrolambda"),
                                element(name("artifactId"), "retrolambda"),
                                element(name("version"), version),
                                element(name("overWrite"), "true"),
                                element(name("outputDirectory"), getRetrolambdaJarDir()),
                                element(name("destFileName"), getRetrolambdaJarName())))),
                executionEnvironment(project, session, pluginManager));
    }

    String getJavaCommand() {
      String javaCommand = null;

      List<Toolchain> tcCandidates;
      tcCandidates = toolchainManager.getToolchains(session, "jdk", Collections
          .singletonMap("version", "1.8"));
      for (Toolchain tc : tcCandidates) {
          String cmd = tc.findTool("java");
          if (cmd != null) {
              getLog().info("Toolchain in retrolambda-maven-plugin: " + tc);
              javaCommand = cmd;
              break;
          }
      }

      if (javaCommand == null) {
        tcCandidates = toolchainManager.getToolchains(session, "jdk", Collections.singletonMap(
            "version", "8"));
        for (Toolchain tc : tcCandidates) {
          String cmd = tc.findTool("java");
          if (cmd != null) {
            getLog().info("Toolchain in retrolambda-maven-plugin: " + tc);
            javaCommand = cmd;
            break;
          }
        }
      }


      Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
      if (javaCommand == null && tc != null) {
          getLog().info("Toolchain in retrolambda-maven-plugin: " + tc);
          javaCommand = tc.findTool("java");
      }

      if (java8home != null) {
          if (tc != null) {
              getLog().warn("Toolchains are ignored, 'java8home' parameter is set to " + java8home);
          }
          javaCommand = getJavaCommand(java8home);
      }

      if (javaCommand == null) {
          javaCommand = getJavaCommand(new File(System.getProperty("java.home")));
      }

      return javaCommand;
    }

    private static String getJavaCommand(File javaHome) {
        return new File(javaHome, "bin/java").getPath();
    }

    private String getClasspath() {
        try {
            return Joiner.on(File.pathSeparator).join(getClasspathElements());
        } catch (DependencyResolutionRequiredException e) {
            throw new RuntimeException(e);
        }
    }

    private File getClasspathFile() {
        try {
            String classpath = Joiner.on("\n").join(getClasspathElements());
            File file = File.createTempFile("retrolambda", "classpath");
            file.deleteOnExit();
            Files.write(classpath, file, Charsets.UTF_8);
            return file;
        } catch (DependencyResolutionRequiredException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getRetrolambdaJarPath() {
        return getRetrolambdaJarDir() + "/" + getRetrolambdaJarName();
    }

    private String getRetrolambdaJarDir() {
        return project.getBuild().getDirectory() + "/retrolambda";
    }

    private String getRetrolambdaJarName() {
        return "retrolambda.jar";
    }

    private static String getRetrolambdaVersion() throws MojoExecutionException {
        try {
            InputStream is = ProcessClassesMojo.class.getResourceAsStream(
                    "/META-INF/maven/com.kohlschutter.retrolambda/retrolambda-maven-plugin/pom.properties");
            try {
                Properties p = new Properties();
                p.load(is);
                return p.getProperty("version");
            } finally {
                is.close();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to detect the Retrolambda version", e);
        }
    }
}
