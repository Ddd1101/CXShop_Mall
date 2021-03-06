package com.wayn.data.elastic.manager;

import com.alibaba.fastjson.JSON;
import com.wayn.data.elastic.config.ElasticConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@AllArgsConstructor
public class ElasticDocument implements DisposableBean {

    private ElasticConfig elasticConfig;
    private RestHighLevelClient restHighLevelClient;

    /**
     * ????????????
     *
     * @param idxName ????????????
     * @param idxSQL  ??????????????????
     */
    public boolean createIndex(String idxName, String idxSQL) throws IOException {
        if (indexExist(idxName)) {
            log.info("idxName={} ????????????,idxSql={}", idxName, idxSQL);
            return false;
        }
        CreateIndexRequest request = new CreateIndexRequest(idxName);
        buildSetting(request);
        request.mapping(idxSQL, XContentType.JSON);
        // request.settings() ????????????Setting
        CreateIndexResponse res = restHighLevelClient.indices().create(request, RequestOptions.DEFAULT);
        return res.isAcknowledged();
    }

    /**
     * ?????????index????????????
     *
     * @param idxName ????????????
     * @return boolean
     * @throws IOException ??????
     */
    public boolean indexExist(String idxName) throws IOException {
        GetIndexRequest request = new GetIndexRequest(idxName);
        request.local(false);
        request.humanReadable(true);
        request.includeDefaults(false);
        // request.indicesOptions(IndicesOptions.lenientExpandOpen());
        return restHighLevelClient.indices().exists(request, RequestOptions.DEFAULT);
    }

    /**
     * ?????????????????????
     *
     * @param request ??????????????????
     */
    public void buildSetting(CreateIndexRequest request) {
        request.settings(Settings.builder().put("index.number_of_shards", elasticConfig.getShards())
                .put("index.number_of_replicas", elasticConfig.getReplicas()));
    }

    /**
     * @param idxName index
     * @param entity  ??????
     */
    public boolean insertOrUpdateOne(String idxName, ElasticEntity entity) throws IOException {
        IndexRequest request = new IndexRequest(idxName);
        log.info("Data : id={},entity={}", entity.getId(), JSON.toJSONString(entity.getData()));
        request.id(entity.getId());
        request.source(entity.getData(), XContentType.JSON);
        IndexResponse indexResponse = restHighLevelClient.index(request, RequestOptions.DEFAULT);
        ReplicationResponse.ShardInfo shardInfo = indexResponse.getShardInfo();
        if (shardInfo.getFailed() > 0) {
            for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                log.error(failure.reason(), failure.getCause());
            }
        }
        return indexResponse.status().equals(RestStatus.OK);
    }


    /**
     * ??????????????????
     *
     * @param idxName index
     * @param list    ???????????????
     */
    public boolean insertBatch(String idxName, List<ElasticEntity> list) throws IOException {
        BulkRequest request = new BulkRequest();
        list.forEach(item -> request.add(new IndexRequest(idxName).id(item.getId())
                .source(item.getData(), XContentType.JSON)));
        BulkResponse bulkResponse = restHighLevelClient.bulk(request, RequestOptions.DEFAULT);
        return bulkResponseHandler(bulkResponse);
    }

    /**
     * bulkResponse ??????
     *
     * @param bulkResponse ??????????????????
     * @return boolean
     */
    private boolean bulkResponseHandler(BulkResponse bulkResponse) {
        boolean flag = true;
        for (BulkItemResponse response : bulkResponse) {
            if (response.isFailed()) {
                flag = false;
                BulkItemResponse.Failure failure = response.getFailure();
                log.error(failure.getMessage(), failure.getCause());
            }
        }
        return flag;
    }

    /**
     * ????????????
     *
     * @param idxName index
     * @param idList  ???????????????
     */
    public <T> boolean deleteBatch(String idxName, Collection<T> idList) throws IOException {
        BulkRequest request = new BulkRequest();
        idList.forEach(item -> request.add(new DeleteRequest(idxName, item.toString())));
        BulkResponse bulk = restHighLevelClient.bulk(request, RequestOptions.DEFAULT);
        return bulkResponseHandler(bulk);
    }

    /**
     * ????????????
     *
     * @param idxName ????????????
     * @param id      ??????ID
     * @return boolean
     */
    public boolean delete(String idxName, String id) throws IOException {
        DeleteRequest request = new DeleteRequest(idxName, id);
        DeleteResponse deleteResponse = restHighLevelClient.delete(request, RequestOptions.DEFAULT);
        ReplicationResponse.ShardInfo shardInfo = deleteResponse.getShardInfo();
        if (shardInfo.getFailed() > 0) {
            for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                log.error(failure.reason());
            }
        }
        return deleteResponse.status().equals(RestStatus.OK);
    }

    /**
     * ????????????
     *
     * @param idxName index
     * @param builder ????????????
     * @param c       ???????????????
     * @return java.util.List<T>
     */
    public <T> List<T> search(String idxName, SearchSourceBuilder builder, Class<T> c) throws IOException {
        SearchRequest request = new SearchRequest(idxName);
        request.source(builder);
        SearchResponse response = restHighLevelClient.search(request, RequestOptions.DEFAULT);
        SearchHit[] hits = response.getHits().getHits();
        return Arrays.stream(hits).map(hit -> JSON.parseObject(hit.getSourceAsString(), c)).collect(Collectors.toList());
    }


    /**
     * ?????????http???????????????????????????????????????
     *
     * @param request ??????????????????
     * @param c       ???????????????
     * @return java.util.List<T>
     */
    public <T> List<T> msearch(MultiSearchRequest request, Class<T> c) throws IOException {
        MultiSearchResponse response = restHighLevelClient.msearch(request, RequestOptions.DEFAULT);
        MultiSearchResponse.Item[] responseResponses = response.getResponses();
        List<T> all = new ArrayList<>();
        for (MultiSearchResponse.Item item : responseResponses) {
            SearchHits hits = item.getResponse().getHits();
            List<T> res = new ArrayList<>(hits.getHits().length);
            for (SearchHit hit : hits) {
                res.add(JSON.parseObject(hit.getSourceAsString(), c));
            }
            all.addAll(res);
        }
        return all;
    }

    /**
     * ??????index
     *
     * @param idxName ????????????
     * @return boolean
     */
    public boolean deleteIndex(String idxName) throws IOException {
        if (!this.indexExist(idxName)) {
            log.error(" idxName={} ?????????", idxName);
            return false;
        }
        AcknowledgedResponse acknowledgedResponse = restHighLevelClient.indices()
                .delete(new DeleteIndexRequest(idxName), RequestOptions.DEFAULT);
        return acknowledgedResponse.isAcknowledged();
    }


    /**
     * ??????????????????????????????
     *
     * @param idxName ????????????
     * @param builder ????????????
     * @return boolean
     */
    public boolean deleteByQuery(String idxName, QueryBuilder builder) throws IOException {
        DeleteByQueryRequest request = new DeleteByQueryRequest(idxName);
        request.setQuery(builder);
        // ????????????????????????,?????????10000
        request.setBatchSize(100);
        // ???????????????????????????
        request.setConflicts("proceed");
        BulkByScrollResponse bulkByScrollResponse = restHighLevelClient.deleteByQuery(request, RequestOptions.DEFAULT);
        List<BulkItemResponse.Failure> bulkFailures = bulkByScrollResponse.getBulkFailures();
        boolean flag = true;
        for (BulkItemResponse.Failure bulkFailure : bulkFailures) {
            log.error(bulkFailure.getMessage(), bulkFailure.getCause());
            flag = false;
        }
        return flag;
    }

    /**
     * bean??????????????????????????????????????????
     */
    @Override
    public void destroy() {
        try {
            if (restHighLevelClient != null) {
                restHighLevelClient.close();
            }
        } catch (Exception exception) {
            log.error(exception.getMessage(), exception);
        }
    }
}
