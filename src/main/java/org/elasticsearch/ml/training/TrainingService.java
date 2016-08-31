package org.elasticsearch.ml.training;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.ml.training.ModelTrainer.TrainingSession;
import org.elasticsearch.search.SearchRequestParsers;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic service for training models using aggregations framework.
 */
public class TrainingService extends AbstractComponent {
    private final ModelTrainers modelTrainers;
    private final Client client;
    private final ClusterService clusterService;
    private final SearchRequestParsers searchRequestParsers;
    private final ParseFieldMatcher parseFieldMatcher;

    public TrainingService(Settings settings, ClusterService clusterService, Client client, ModelTrainers modelTrainers,
                           SearchRequestParsers searchRequestParsers) {
        super(settings);
        this.clusterService = clusterService;
        this.modelTrainers = modelTrainers;
        this.client = client;
        this.searchRequestParsers = searchRequestParsers;
        this.parseFieldMatcher = new ParseFieldMatcher(settings);
    }

    public void train(String modelType, Settings modelSettings, String index, String type, Map<String, Object> query,
                      List<ModelInputField> fields, ModelTargetField outputField, ActionListener<String> listener) {
        try {
            MetaData metaData = clusterService.state().getMetaData();
            AliasOrIndex aliasOrIndex = metaData.getAliasAndIndexLookup().get(index);
            if (aliasOrIndex == null) {
                throw new IndexNotFoundException("the training index [" + index + "] not found");
            }
            if (aliasOrIndex.getIndices().size() != 1) {
                throw new IndexNotFoundException("can only train on a single index");
            }
            IndexMetaData indexMetaData = aliasOrIndex.getIndices().get(0);
            CompressedXContent filter;
            if (aliasOrIndex.isAlias()) {
                filter = indexMetaData.getAliases().get(index).getFilter();
            } else {
                filter = null;
            }

            MappingMetaData mappingMetaData = indexMetaData.mapping(type);
            if (mappingMetaData == null) {
                throw new ResourceNotFoundException("the training type [" + type + "] not found");
            }

            TrainingSession trainingSession = modelTrainers.createTrainingSession(mappingMetaData, modelType, modelSettings, fields, outputField);

            client.prepareSearch().setIndices(index)
                    .setQuery(combineQueryAndAliasFilter(query, filter))
                    .addAggregation(trainingSession.trainingRequest())
                    .execute(new ActionListener<SearchResponse>() {
                        @Override
                        public void onResponse(SearchResponse response) {
                            try {
                                listener.onResponse(trainingSession.model(response));
                            } catch (Exception ex) {
                                listener.onFailure(ex);
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            listener.onFailure(e);
                        }
                    });

        } catch (Exception ex) {
            listener.onFailure(ex);
        }
    }

    private QueryBuilder combineQueryAndAliasFilter(Map<String, Object> query, CompressedXContent filter) {
        Optional<QueryBuilder> queryBuilder;
        if (query != null) {
            String queryString;
            try {
                queryString = XContentFactory.contentBuilder(XContentType.JSON).map(query).string();
            }catch (IOException ex) {
                throw new IllegalArgumentException("Cannot parse query [" + query + "]", ex);
            }
            queryBuilder = parseQuery(queryString);
        } else {
            queryBuilder = Optional.empty();
        }

        final QueryBuilder finalQuery;
        if (filter != null) {
            BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
            if (queryBuilder.isPresent()) {
                boolQueryBuilder.must(queryBuilder.get());
            }
            Optional<QueryBuilder> filterQueryBuilder = parseQuery(filter.toString());
            finalQuery = boolQueryBuilder.filter(
                    filterQueryBuilder.orElseThrow(() -> new IllegalArgumentException("couldn't parse filter")));
        } else {
            if (queryBuilder.isPresent()) {
                finalQuery = queryBuilder.get();
            } else {
                finalQuery = QueryBuilders.matchAllQuery();
            }
        }
        return finalQuery;
    }

    private Optional<QueryBuilder> parseQuery(String filterString) {
        try (XContentParser parser = XContentFactory.xContent(filterString).createParser(filterString)) {
            QueryParseContext context = new QueryParseContext(searchRequestParsers.queryParsers, parser, parseFieldMatcher);
            return context.parseInnerQueryBuilder();
        } catch (IOException e) {
            throw new ElasticsearchException(e);
        }

    }
}
