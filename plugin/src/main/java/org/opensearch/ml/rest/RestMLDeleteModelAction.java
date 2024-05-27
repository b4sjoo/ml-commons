/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest;

import static org.opensearch.ml.plugin.MachineLearningPlugin.ML_BASE_URI;
import static org.opensearch.ml.utils.RestActionUtils.PARAMETER_MAX_RETRY;
import static org.opensearch.ml.utils.RestActionUtils.PARAMETER_MODEL_ID;
import static org.opensearch.ml.utils.RestActionUtils.PARAMETER_RETRY;
import static org.opensearch.ml.utils.RestActionUtils.PARAMETER_RETRY_DELAY;
import static org.opensearch.ml.utils.RestActionUtils.PARAMETER_RETRY_TIMEOUT;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.opensearch.client.node.NodeClient;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.ml.common.transport.model.MLModelDeleteAction;
import org.opensearch.ml.common.transport.model.MLModelDeleteRequest;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;

import com.google.common.collect.ImmutableList;

/**
 * This class consists of the REST handler to delete ML Model.
 */
public class RestMLDeleteModelAction extends BaseRestHandler {
    private static final String ML_DELETE_MODEL_ACTION = "ml_delete_model_action";

    public void RestMLDeleteModelAction() {}

    @Override
    public String getName() {
        return ML_DELETE_MODEL_ACTION;
    }

    @Override
    public List<Route> routes() {
        return ImmutableList
            .of(new Route(RestRequest.Method.DELETE, String.format(Locale.ROOT, "%s/models/{%s}", ML_BASE_URI, PARAMETER_MODEL_ID)));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String modelId = request.param(PARAMETER_MODEL_ID);
        boolean retry = request.paramAsBoolean(PARAMETER_RETRY, true);
        int maxRetry = request.paramAsInt(PARAMETER_MAX_RETRY, 1);
        TimeValue retryDelay = request.paramAsTime(PARAMETER_RETRY_DELAY, TimeValue.timeValueMillis(100));
        TimeValue retryTimeout = request.paramAsTime(PARAMETER_RETRY_TIMEOUT, TimeValue.timeValueSeconds(30));

        MLModelDeleteRequest mlModelDeleteRequest = new MLModelDeleteRequest(modelId, retry, maxRetry, retryDelay, retryTimeout);
        return channel -> client.execute(MLModelDeleteAction.INSTANCE, mlModelDeleteRequest, new RestToXContentListener<>(channel));
    }
}
