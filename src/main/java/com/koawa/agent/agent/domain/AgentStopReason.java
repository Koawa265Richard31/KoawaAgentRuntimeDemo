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

public enum AgentStopReason {

    /**
     * 已获得最终回答
     */
    FINAL_ANSWER,

    /**
     *超过最大步数，防止死循环
     */
    MAX_STEPS,

    /**
     * Agent turn exceeded its configured deadline.
     */
    TIMEOUT,

    /**
     * planner / executor 异常
     */
    ERROR,

    /**
     * 信息不足，需要用户补充
     */
    ASK_CLARIFICATION,

    /**
     * 用户主动取消本次任务
     */
    CANCELLED,
}
