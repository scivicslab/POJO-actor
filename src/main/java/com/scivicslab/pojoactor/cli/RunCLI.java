/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.pojoactor.cli;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.InterpreterIIAR;
import com.scivicslab.pojoactor.workflow.VarsActor;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI subcommand for running YAML/JSON workflows.
 *
 * <p>Actor tree structure: ROOT -&gt; InterpreterIIAR</p>
 *
 * <p>Usage:</p>
 * <pre>
 * pojo-actor run -d ./workflows -w hello.yaml
 * pojo-actor run -w ./hello.yaml
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.13.0
 */
@Command(
    name = "run",
    description = "Run a YAML/JSON workflow",
    mixinStandardHelpOptions = true
)
public class RunCLI implements Callable<Integer> {

    @Option(
        names = {"-d", "--directory"},
        description = "Base directory for workflow files",
        defaultValue = "."
    )
    private File baseDirectory;

    @Option(
        names = {"-w", "--workflow"},
        description = "Workflow file path (relative to base directory)",
        required = true
    )
    private String workflowFile;

    @Option(
        names = {"-m", "--max-iterations"},
        description = "Maximum iterations (default: 10000)",
        defaultValue = "10000"
    )
    private int maxIterations;

    @Option(
        names = {"-o", "--overlay"},
        description = "Overlay directory for kustomize"
    )
    private File overlayDirectory;

    @Option(
        names = {"-P"},
        description = "Define a variable (e.g., -Pname=value)"
    )
    private Map<String, String> variables = new HashMap<>();

    @Override
    public Integer call() throws Exception {
        // Resolve workflow path
        Path workflowPath = baseDirectory.toPath().resolve(workflowFile);
        if (!workflowPath.toFile().exists()) {
            System.err.println("Workflow file not found: " + workflowPath);
            return 1;
        }

        // Create actor system
        IIActorSystem system = new IIActorSystem("pojo-actor");

        // Create interpreter
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("interpreter")
            .team(system)
            .build();
        interpreter.setWorkflowBaseDir(baseDirectory.getAbsolutePath());

        // Create vars actor and register
        VarsActor varsActor = new VarsActor(system, variables);
        system.addIIActor(varsActor);

        // Create interpreter actor and register
        InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        system.addIIActor(interpreterActor);

        // Load workflow
        if (overlayDirectory != null) {
            interpreter.readYaml(workflowPath, overlayDirectory.toPath());
        } else {
            interpreter.readYaml(workflowPath);
        }

        // Run workflow
        ActionResult result = interpreter.runUntilEnd(maxIterations);

        // Output result
        if (result.isSuccess()) {
            System.out.println("Workflow completed successfully.");
            return 0;
        } else {
            System.err.println("Workflow failed: " + result.getResult());
            return 1;
        }
    }
}
