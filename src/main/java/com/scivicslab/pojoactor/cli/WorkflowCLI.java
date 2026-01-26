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

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main CLI entry point for POJO-actor workflow interpreter.
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar pojo-actor-2.13.0.jar run -d ./ -w hello.yaml
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.13.0
 */
@Command(
    name = "pojo-actor",
    description = "POJO-actor workflow interpreter",
    mixinStandardHelpOptions = true,
    version = "2.13.0",
    subcommands = {
        RunCLI.class
    }
)
public class WorkflowCLI implements Runnable {

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new WorkflowCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // Show help when no subcommand is specified
        new CommandLine(this).usage(System.out);
    }
}
