/*
 * Copyright (c) 2018, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ballerinalang.platform.playground.api.core.phase;

import org.apache.commons.io.IOUtils;
import org.ballerinalang.platform.playground.api.core.Constants;
import org.ballerinalang.platform.playground.api.core.RunSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Build Phase of Run
 */
public class BuildPhase implements Phase {

    private volatile boolean buildPassed = true;

    private static final Logger logger = LoggerFactory.getLogger(BuildPhase.class);

    /**
     * Construct the command array to be executed for compiling.
     * @return String[] command array
     */
    private String[] getBuildCommandArray(RunSession runSession) throws IOException {
        List<String> commandList = new ArrayList<>();
        // path to ballerina
        commandList.add("ballerina");
        commandList.add("build");
        commandList.add(runSession.getSourceFile().getFileName().toString());
        return commandList.toArray(new String[0]);
    }

    @Override
    public void execute(RunSession runSession, Runnable next) throws Exception {
        String[] cmdArray = getBuildCommandArray(runSession);
        Instant buildStart = Instant.now();
        Process buildProcess = Runtime.getRuntime().exec(cmdArray, null, runSession.getSourceRoot().toFile());
        runSession.pushMessageToClient(Constants.CONTROL_MSG, Constants.BUILD_STARTED,
                "building...");

        new Thread(() -> {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(buildProcess.getInputStream(), Charset
                        .defaultCharset()));
                String line = "";
                while ((line = reader.readLine()) != null) {
                    String processedMsg = runSession.processConsoleMessage(line);
                    runSession.pushMessageToClient(Constants.DATA_MSG, Constants.OUTPUT, processedMsg);
                }
                Instant buildStop = Instant.now();
                Duration buildTime = Duration.between(buildStart, buildStop);
                if (buildPassed) {
                    runSession.pushMessageToClient(Constants.CONTROL_MSG, Constants.BUILD_STOPPED,
                            "build completed in " + buildTime.toMillis() + "ms");
                    next.run();
                }
            } catch (IOException e) {
                logger.error("Error while sending build output stream.");
            } finally {
                if (reader != null) {
                    IOUtils.closeQuietly(reader);
                }
            }
        }).start();

        new Thread(() -> {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(
                        buildProcess.getErrorStream(), Charset.defaultCharset()));
                String line = "";
                while ((line = reader.readLine()) != null) {
                    if (buildPassed) {
                        buildPassed = false;
                        Instant buildStop = Instant.now();
                        Duration buildTime = Duration.between(buildStart, buildStop);
                        runSession.pushMessageToClient(
                                Constants.CONTROL_MSG,
                                Constants.BUILD_STOPPED_WITH_ERRORS,
                                "build failed with errors in " + buildTime.toMillis() + "ms");

                    }
                    String processedMsg = runSession.processConsoleMessage(line);
                    runSession.pushMessageToClient(Constants.DATA_MSG, Constants.BUILD_ERROR, processedMsg);
                }
            } catch (IOException e) {
                logger.error("Error while sending build error stream.", e);
            } finally {
                if (reader != null) {
                    IOUtils.closeQuietly(reader);
                }
            }
        }).start();
    }
}
