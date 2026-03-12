//usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JBang script to launch the POJO-actor workflow interpreter.
 *
 * <p>This script locates the pojo-actor JAR from the Maven local repository
 * and executes it with the provided command line arguments.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * ./pojo-actor.java run -d ./ -w hello.yaml
 * ./pojo-actor.java run -w ./workflows/example.yaml
 * ./pojo-actor.java --help
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.13.0
 */
class pojo_actor {

    private static final String VERSION = "2.13.0";

    public static void main(String[] args) throws Exception {
        Path jarPath = Paths.get(
            System.getProperty("user.home"),
            ".m2", "repository", "com", "scivicslab", "pojo-actor",
            VERSION, "pojo-actor-" + VERSION + ".jar"
        );

        File jarFile = jarPath.toFile();
        if (!jarFile.exists()) {
            System.err.println("pojo-actor JAR not found: " + jarPath);
            System.err.println();
            System.err.println("Please build and install POJO-actor first:");
            System.err.println("  cd POJO-actor");
            System.err.println("  mvn install");
            System.exit(1);
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        command.addAll(Arrays.asList(args));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        System.exit(exitCode);
    }
}
