/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.task;

import static org.opensearch.ml.plugin.MachineLearningPlugin.TASK_THREAD_POOL;

import lombok.extern.log4j.Log4j2;

import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionListenerResponseHandler;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.ml.common.breaker.MLCircuitBreakerService;
import org.opensearch.ml.common.parameter.Input;
import org.opensearch.ml.common.parameter.Output;
import org.opensearch.ml.common.transport.execute.MLExecuteTaskAction;
import org.opensearch.ml.common.transport.execute.MLExecuteTaskRequest;
import org.opensearch.ml.common.transport.execute.MLExecuteTaskResponse;
import org.opensearch.ml.engine.MLEngine;
import org.opensearch.ml.indices.MLInputDatasetHandler;
import org.opensearch.ml.stats.MLStats;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportResponseHandler;

/**
 * MLExecuteTaskRunner is responsible for running execute tasks.
 */
@Log4j2
public class MLExecuteTaskRunner extends MLTaskRunner<MLExecuteTaskRequest, MLExecuteTaskResponse> {
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final Client client;
    private final MLInputDatasetHandler mlInputDatasetHandler;

    public MLExecuteTaskRunner(
        ThreadPool threadPool,
        ClusterService clusterService,
        Client client,
        MLTaskManager mlTaskManager,
        MLStats mlStats,
        MLInputDatasetHandler mlInputDatasetHandler,
        MLTaskDispatcher mlTaskDispatcher,
        MLCircuitBreakerService mlCircuitBreakerService
    ) {
        super(mlTaskManager, mlStats, mlTaskDispatcher, mlCircuitBreakerService, clusterService);
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.client = client;
        this.mlInputDatasetHandler = mlInputDatasetHandler;
    }

    @Override
    protected String getTransportActionName() {
        return MLExecuteTaskAction.NAME;
    }

    @Override
    protected TransportResponseHandler<MLExecuteTaskResponse> getResponseHandler(ActionListener<MLExecuteTaskResponse> listener) {
        return new ActionListenerResponseHandler<>(listener, MLExecuteTaskResponse::new);
    }

    /**
     * Execute algorithm and return result.
     * @param request MLExecuteTaskRequest
     * @param listener Action listener
     */
    @Override
    protected void executeTask(MLExecuteTaskRequest request, ActionListener<MLExecuteTaskResponse> listener) {
        threadPool.executor(TASK_THREAD_POOL).execute(() -> {
            Input input = request.getInput();
            Output output = MLEngine.execute(input);
            MLExecuteTaskResponse response = MLExecuteTaskResponse.builder().output(output).build();
            listener.onResponse(response);
        });
    }

}
