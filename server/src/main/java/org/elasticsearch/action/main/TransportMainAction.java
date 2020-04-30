/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.main;

import org.elasticsearch.Build;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.Node;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;

public class TransportMainAction extends HandledTransportAction<MainRequest, MainResponse> {

    private final String nodeName;
    private final ClusterService clusterService;

    @Inject
    public TransportMainAction(Settings settings, TransportService transportService,
                               ActionFilters actionFilters, ClusterService clusterService) {
        super(MainAction.NAME, transportService, actionFilters, MainRequest::new);
        this.nodeName = Node.NODE_NAME_SETTING.get(settings);
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(Task task, MainRequest request, ActionListener<MainResponse> listener) {
        ClusterState clusterState = clusterService.state();
        /**
         * 这个地方开始封装响应消息,所有的响应消息都在MainResponse对象中
         * nodeName,Version.CURRENT都是要返回给用户的消息
         */
        /**
         * 返回数据例子（6.7.1）
         * {
         *   "name" : "O5yzw5H",
         *   "cluster_name" : "guluo",
         *   "cluster_uuid" : "3PXsLGVsT7quebOjjYJATw",
         *   "version" : {
         *     "number" : "6.7.1",
         *     "build_flavor" : "default",
         *     "build_type" : "tar",
         *     "build_hash" : "Unknown",
         *     "build_date" : "2019-08-20T08:36:32.803351Z",
         *     "build_snapshot" : true,
         *     "lucene_version" : "7.7.0",
         *     "minimum_wire_compatibility_version" : "5.6.0",
         *     "minimum_index_compatibility_version" : "5.0.0"
         *   },
         *   "tagline" : "You Know, for Search"
         * }
         */
        listener.onResponse(
            new MainResponse(nodeName, Version.CURRENT, clusterState.getClusterName(),
                    clusterState.metadata().clusterUUID(), Build.CURRENT));
    }
}
