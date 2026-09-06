/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scivicslab.pojoactor.core;

import javax.tools.ToolProvider;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds plugin JARs at test time by compiling source strings with the system Java compiler.
 * Used by DynamicActorLoaderTest and ActorProviderSPITest.
 */
class TestPluginJarBuilder {

    static final String MATH_PLUGIN_CLASS = "com.example.testplugin.TestMathPlugin";
    static final String MATH_PLUGIN_PROVIDER_CLASS = "com.example.testplugin.TestMathPluginProvider";
    static final String ACTOR_PROVIDER_SPI_FQCN = "com.scivicslab.pojoactor.core.ActorProvider";

    private static final String MATH_PLUGIN_SOURCE =
        "package com.example.testplugin;\n"
        + "import com.scivicslab.pojoactor.core.TestCalculator;\n"
        + "public class TestMathPlugin implements TestCalculator {\n"
        + "    private int lastResult = 0;\n"
        + "    @Override\n"
        + "    public String add(String args) {\n"
        + "        String[] p = args.split(\",\");\n"
        + "        if (p.length != 2) return \"add requires a,b\";\n"
        + "        lastResult = Integer.parseInt(p[0].trim()) + Integer.parseInt(p[1].trim());\n"
        + "        return String.valueOf(lastResult);\n"
        + "    }\n"
        + "    @Override\n"
        + "    public String lastResult() { return String.valueOf(lastResult); }\n"
        + "}\n";

    private static final String MATH_PLUGIN_PROVIDER_SOURCE =
        "package com.example.testplugin;\n"
        + "import com.scivicslab.pojoactor.core.ActorProvider;\n"
        + "import com.scivicslab.pojoactor.core.ActorSystem;\n"
        + "public class TestMathPluginProvider implements ActorProvider {\n"
        + "    @Override\n"
        + "    public void registerActors(ActorSystem system) {\n"
        + "        system.actorOf(\"math\", new TestMathPlugin());\n"
        + "    }\n"
        + "    @Override public String getPluginName() { return \"TestMathPlugin\"; }\n"
        + "    @Override public String getPluginVersion() { return \"1.0.0-test\"; }\n"
        + "}\n";

    /** JAR containing only TestMathPlugin (for DynamicActorLoaderTest). */
    static Path buildMathPluginJar() throws Exception {
        return buildJar(false, MATH_PLUGIN_SOURCE);
    }

    /** JAR containing TestMathPlugin + TestMathPluginProvider + SPI services file (for ActorProviderSPITest). */
    static Path buildMathPluginProviderJar() throws Exception {
        return buildJar(true, MATH_PLUGIN_SOURCE, MATH_PLUGIN_PROVIDER_SOURCE);
    }

    // -------------------------------------------------------------------------

    private static Path buildJar(boolean includeSpiServicesFile, String... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "javax.tools.JavaCompiler not available — tests must run with a JDK, not JRE");
        }

        Path srcDir = Files.createTempDirectory("plugin-src");
        Path classesDir = Files.createTempDirectory("plugin-classes");

        // Write source files into package-matching directory tree
        var sourceFilePaths = new ArrayList<String>();
        for (String source : sources) {
            String pkg = extractPackage(source);
            String className = extractPublicClassName(source);
            Path pkgDir = srcDir.resolve(pkg.replace('.', '/'));
            Files.createDirectories(pkgDir);
            Path srcFile = pkgDir.resolve(className + ".java");
            Files.writeString(srcFile, source);
            sourceFilePaths.add(srcFile.toString());
        }

        // Compile against the test classpath so POJO-actor types are visible
        var errOut = new ByteArrayOutputStream();
        var args = new ArrayList<String>();
        args.add("-classpath");
        args.add(System.getProperty("java.class.path"));
        args.add("-d");
        args.add(classesDir.toString());
        args.addAll(sourceFilePaths);

        int rc = compiler.run(null, null, new PrintStream(errOut), args.toArray(new String[0]));
        if (rc != 0) {
            throw new RuntimeException("Plugin compilation failed:\n" + errOut);
        }

        // Pack compiled classes into a JAR
        Path jarPath = Files.createTempFile("test-plugin", ".jar");
        try (var jos = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(jarPath)))) {
            try (var walk = Files.walk(classesDir)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        try {
                            String entryName = classesDir.relativize(p).toString().replace('\\', '/');
                            jos.putNextEntry(new JarEntry(entryName));
                            jos.write(Files.readAllBytes(p));
                            jos.closeEntry();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to pack class: " + p, e);
                        }
                    });
            }
            if (includeSpiServicesFile) {
                jos.putNextEntry(new JarEntry("META-INF/services/" + ACTOR_PROVIDER_SPI_FQCN));
                jos.write(MATH_PLUGIN_PROVIDER_CLASS.getBytes());
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    private static String extractPackage(String source) {
        return source.lines()
            .filter(l -> l.startsWith("package "))
            .findFirst()
            .map(l -> l.replace("package ", "").replace(";", "").trim())
            .orElse("");
    }

    private static String extractPublicClassName(String source) {
        return source.lines()
            .filter(l -> l.contains("public class "))
            .findFirst()
            .map(l -> l.replaceAll(".*public class (\\w+).*", "$1"))
            .orElseThrow(() -> new IllegalArgumentException("No public class found in source"));
    }
}
