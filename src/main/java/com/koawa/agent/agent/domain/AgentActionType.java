/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.koawa.agent.agent.domain;

public enum AgentActionType {
    /**
     * 调用 MCP 工具。
     */
    CALL_MCP_TOOL,
    /**
     * 信息不足，需要用户澄清。
     */
    ASK_CLARIFICATION,
    /**
     * 信息足够，输出最终回答。
     */
    FINAL_ANSWER;

    public boolean isTerminal() {
        return this == FINAL_ANSWER || this == ASK_CLARIFICATION;
    }
}
