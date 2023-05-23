/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.handler;

import static org.opensearch.rest.RestStatus.BAD_REQUEST;
import static org.opensearch.rest.RestStatus.INTERNAL_SERVER_ERROR;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.extern.log4j.Log4j2;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionListener;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Client;
import org.opensearch.common.util.CollectionUtils;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.ExistsQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.TermsQueryBuilder;
import org.opensearch.indices.InvalidIndexNameException;
import org.opensearch.ml.common.CommonValue;
import org.opensearch.ml.common.MLModelGroup;
import org.opensearch.ml.common.exception.MLException;
import org.opensearch.ml.common.exception.MLResourceNotFoundException;
import org.opensearch.ml.helper.ModelAccessControlHelper;
import org.opensearch.ml.utils.RestActionUtils;
import org.opensearch.rest.RestStatus;
import org.opensearch.search.builder.SearchSourceBuilder;

import com.google.common.base.Throwables;

import lombok.extern.log4j.Log4j2;

/**
 * Handle general get and search request in ml common.
 */
@Log4j2
public class MLSearchHandler {
    private final Client client;
    private NamedXContentRegistry xContentRegistry;

    private ModelAccessControlHelper modelAccessControlHelper;

    public MLSearchHandler(Client client, NamedXContentRegistry xContentRegistry, ModelAccessControlHelper modelAccessControlHelper) {
        this.modelAccessControlHelper = modelAccessControlHelper;
        this.client = client;
        this.xContentRegistry = xContentRegistry;
    }

    /**
     * Fetch all the models from the model group index, and then create a combined query to model version index.
     * @param request
     * @param actionListener
     */
    public void search(SearchRequest request, ActionListener<SearchResponse> actionListener) {
        User user = RestActionUtils.getUserContext(client);
        if (modelAccessControlHelper.skipModelAccessControl(user)) {
            try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                client.search(request, actionListener);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                actionListener.onFailure(e);
            }
        } else {
            SearchSourceBuilder sourceBuilder = modelAccessControlHelper.createSearchSourceBuilder(user);
            SearchRequest modelGroupSearchRequest = new SearchRequest();
            sourceBuilder.fetchSource(new String[] { MLModelGroup.MODEL_GROUP_ID_FIELD, }, null);
            modelGroupSearchRequest.source(sourceBuilder);
            modelGroupSearchRequest.indices(CommonValue.ML_MODEL_GROUP_INDEX);
            ActionListener<SearchResponse> modelGroupSearchActionListener = ActionListener.wrap(r -> {
                ActionListener<SearchResponse> listener = wrapRestActionListener(actionListener, "Fail to search model version");
                if (r != null && r.getHits() != null && r.getHits().getTotalHits().value > 0) {
                    List<String> modelGroupIds = new ArrayList<>();
                    Arrays.stream(r.getHits().getHits()).forEach(hit -> { modelGroupIds.add(hit.getId()); });
                    try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                        request.source().query(rewriteQueryBuilder(request.source().query(), modelGroupIds));
                        client.search(request, listener);
                    } catch (Exception e) {
                        log.error("Failed to search", e);
                        listener.onFailure(e);
                    }
                } else {
                    log.debug("No model group found");
                    request.source().query(rewriteQueryBuilder(request.source().query(), null));
                    client.search(request, listener);
                }
            }, e -> {
                log.error("Fail to search model groups!", e);
                actionListener.onFailure(e);
            });
            client.search(modelGroupSearchRequest, modelGroupSearchActionListener);
        }
    }

    private QueryBuilder rewriteQueryBuilder(QueryBuilder queryBuilder, List<String> modelGroupIds) {
        ExistsQueryBuilder existsQueryBuilder = new ExistsQueryBuilder(MLModelGroup.MODEL_GROUP_ID_FIELD);
        BoolQueryBuilder modelGroupIdMustNotExistBoolQuery = new BoolQueryBuilder();
        modelGroupIdMustNotExistBoolQuery.mustNot(existsQueryBuilder);

        BoolQueryBuilder accessControlledBoolQuery = new BoolQueryBuilder();
        if (!CollectionUtils.isEmpty(modelGroupIds)) {
            TermsQueryBuilder modelGroupIdTermsQuery = new TermsQueryBuilder(
                MLModelGroup.MODEL_GROUP_ID_FIELD,
                modelGroupIds
            );
            accessControlledBoolQuery.should(modelGroupIdTermsQuery);
        }
        accessControlledBoolQuery.should(modelGroupIdMustNotExistBoolQuery);
        if (queryBuilder == null) {
            return accessControlledBoolQuery;
        } else if (queryBuilder instanceof BoolQueryBuilder) {
            ((BoolQueryBuilder) queryBuilder).must(accessControlledBoolQuery);
            return queryBuilder;
        } else if (modelAccessControlHelper.isSupportedQueryType(queryBuilder.getClass())) {
            BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
            boolQueryBuilder.must(queryBuilder);
            boolQueryBuilder.must(modelGroupIdMustNotExistBoolQuery);
            return boolQueryBuilder;
        }
        throw new IllegalArgumentException(
            "Search API only supports [bool, ids, match, match_all, term, terms, exists, range] query type"
        );
    }

    /**
     * Wrap action listener to avoid return verbose error message and wrong 500 error to user.
     * Suggestion for exception handling in ML common:
     * 1. If the error is caused by wrong input, throw IllegalArgumentException exception.
     * 2. For other errors, please use MLException or its subclass, or use
     *    OpenSearchStatusException.
     *
     * TODO: tune this function for wrapped exception, return root exception error message
     *
     * @param actionListener action listener
     * @param generalErrorMessage general error message
     * @param <T> action listener response type
     * @return wrapped action listener
     */
    public static <T> ActionListener<T> wrapRestActionListener(ActionListener<T> actionListener, String generalErrorMessage) {
        return ActionListener.<T>wrap(r -> { actionListener.onResponse(r); }, e -> {
            log.error("Wrap exception before sending back to user", e);
            Throwable cause = Throwables.getRootCause(e);
            if (isProperExceptionToReturn(e)) {
                actionListener.onFailure(e);
            } else if (isProperExceptionToReturn(cause)) {
                actionListener.onFailure((Exception) cause);
            } else {
                RestStatus status = isBadRequest(e) ? BAD_REQUEST : INTERNAL_SERVER_ERROR;
                String errorMessage = generalErrorMessage;
                if (isBadRequest(e) || e instanceof MLException) {
                    errorMessage = e.getMessage();
                } else if (cause != null && (isBadRequest(cause) || cause instanceof MLException)) {
                    errorMessage = cause.getMessage();
                }
                actionListener.onFailure(new OpenSearchStatusException(errorMessage, status));
            }
        });
    }

    public static boolean isProperExceptionToReturn(Throwable e) {
        return e instanceof OpenSearchStatusException || e instanceof IndexNotFoundException || e instanceof InvalidIndexNameException;
    }

    public static boolean isBadRequest(Throwable e) {
        return e instanceof IllegalArgumentException || e instanceof MLResourceNotFoundException;
    }
}
