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

package com.koawa.agent.agent.mcp;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端配置属性
 */
@Data
@Validated
@ConfigurationProperties(prefix = "agent.mcp")
public class McpClientProperties {

    /**
     * 单次 MCP 同步请求的最长等待时间。
     */
    @NotNull
    @DurationMin(millis = 1)
    private Duration requestTimeout = Duration.ofSeconds(30);

    /**
     * MCP Server 列表
     */
    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {

        /**
         * 服务名称
         */
        private String name;

        /**
         * 服务地址
         */
        private String url;
    }
}
