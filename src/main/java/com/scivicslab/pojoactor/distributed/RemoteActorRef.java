/*
 * Copyright 2025 devteam@scivics-lab.com
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

package com.scivicslab.pojoactor.distributed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.ActionResult;
import com.scivicslab.pojoactor.CallableByActionName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reference to an actor hosted on a remote node.
 *
 * <p>This class acts as a proxy for remote actor invocation. It implements
 * {@link CallableByActionName} and translates method calls into HTTP requests
 * to the remote node.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Register remote node
 * system.registerRemoteNode("node1", "192.168.1.10", 8081);
 *
 * // Get remote actor reference
 * RemoteActorRef remoteMath = system.getRemoteActor("node1", "math");
 *
 * // Call remote actor (synchronous)
 * ActionResult result = remoteMath.callByActionName("add", "5,3");
 * System.out.println("Result: " + result.getResult());
 * }</pre>
 *
 * <h2>Network Protocol</h2>
 * <p>Sends HTTP POST request to:</p>
 * <pre>{@code
 * POST http://node1:8081/actor/math/invoke
 * Content-Type: application/json
 *
 * {
 *   "actionName": "add",
 *   "args": "5,3",
 *   "messageId": "uuid-xxx"
 * }
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @version 3.0.0
 * @since 3.0.0
 */
public class RemoteActorRef implements CallableByActionName {

    private static final Logger logger = Logger.getLogger(RemoteActorRef.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String actorName;
    private final NodeInfo nodeInfo;
    private final HttpClient httpClient;

    /**
     * Constructs a new RemoteActorRef.
     *
     * @param actorName the name of the remote actor
     * @param nodeInfo information about the node hosting the actor
     */
    public RemoteActorRef(String actorName, NodeInfo nodeInfo) {
        this.actorName = actorName;
        this.nodeInfo = nodeInfo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Invokes an action on the remote actor.
     *
     * <p>This method sends an HTTP POST request to the remote node
     * and waits for the response synchronously.</p>
     *
     * @param actionName the name of the action to invoke
     * @param args the arguments for the action
     * @return the result of the action execution
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            // Create message
            ActorMessage message = new ActorMessage(this.actorName, actionName, args);

            // Build HTTP request
            String url = String.format("%s/actor/%s/invoke",
                    nodeInfo.getUrl(), this.actorName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(message.toJson()))
                    .build();

            logger.fine(String.format("Sending request to %s: %s(%s)",
                    url, actionName, args));

            // Send request
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // Parse response
            return parseResponse(response);

        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error invoking remote actor", e);
            return new ActionResult(false,
                    "Network error: " + e.getMessage());
        }
    }

    /**
     * Parses the HTTP response into an ActionResult.
     *
     * @param response the HTTP response
     * @return the parsed ActionResult
     */
    private ActionResult parseResponse(HttpResponse<String> response) {
        try {
            JsonNode json = objectMapper.readTree(response.body());
            boolean success = json.get("success").asBoolean();
            String result = json.get("result").asText();
            return new ActionResult(success, result);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error parsing response", e);
            return new ActionResult(false,
                    "Failed to parse response: " + e.getMessage());
        }
    }

    /**
     * Returns the name of the remote actor.
     *
     * @return the actor name
     */
    public String getActorName() {
        return actorName;
    }

    /**
     * Returns information about the node hosting this actor.
     *
     * @return the node information
     */
    public NodeInfo getNodeInfo() {
        return nodeInfo;
    }

    @Override
    public String toString() {
        return "RemoteActorRef{" +
                "actorName='" + actorName + '\'' +
                ", nodeInfo=" + nodeInfo +
                '}';
    }
}
