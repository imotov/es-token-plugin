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

package org.elasticsearch.action.allterms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.single.shard.TransportSingleShardAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TransportAllTermsShardAction extends TransportSingleShardAction<AllTermsShardRequest, AllTermsSingleShardResponse> {

    private final IndicesService indicesService;

    private static final String ACTION_NAME = AllTermsAction.NAME + "[s]";


    @Inject
    public TransportAllTermsShardAction(Settings settings, ClusterService clusterService, TransportService transportService,
                                        IndicesService indicesService, ThreadPool threadPool, ActionFilters actionFilters,
                                        IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ACTION_NAME, threadPool, clusterService, transportService, actionFilters, indexNameExpressionResolver,
                AllTermsShardRequest::new, ThreadPool.Names.GENERIC);
        this.indicesService = indicesService;
    }

    @Override
    protected boolean isSubAction() {
        return true;
    }

    @Override
    protected AllTermsSingleShardResponse newResponse() {
        return new AllTermsSingleShardResponse(null);
    }

    @Override
    protected boolean resolveIndex(AllTermsShardRequest request) {
        return false;
    }

    @Override
    protected ShardIterator shards(ClusterState state, InternalRequest request) {
        return clusterService.operationRouting()
                .getShards(state, request.concreteIndex(), request.request().shardId(), request.request().preference());
    }

    @Override
    protected AllTermsSingleShardResponse shardOperation(AllTermsShardRequest request, ShardId shardId) throws ElasticsearchException {
        List<String> terms = new ArrayList<>();
        IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
        IndexShard indexShard = indexService.getShard(shardId.id());
        final Engine.Searcher searcher = indexShard.acquireSearcher("all_terms");
        IndexReader topLevelReader = searcher.reader();

        List<LeafReaderContext> leaves = topLevelReader.leaves();

        try {
            if (leaves.size() == 0) {
                return new AllTermsSingleShardResponse(terms);
            }
            getTerms(request, terms, leaves);

            return new AllTermsSingleShardResponse(terms);
        } finally {
            searcher.close();
        }
    }

    protected static void getTerms(AllTermsShardRequest request, List<String> terms, List<LeafReaderContext> leaves) {
        List<TermsEnum> termIters = getTermsEnums(request, leaves);
        CharsRefBuilder spare = new CharsRefBuilder();
        BytesRef lastTerm = null;
        int[] exhausted = new int[termIters.size()];
        for (int i = 0; i < exhausted.length; i++) {
            exhausted[i] = 0;
        }
        try {
            lastTerm = findSmallestTermAfter(request, termIters, lastTerm, exhausted);

            if (lastTerm == null) {
                return;
            }
            findNMoreTerms(request, terms, termIters, spare, lastTerm, exhausted);
        } catch (IOException e) {
        }
    }

    protected static void findNMoreTerms(AllTermsShardRequest request, List<String> terms, List<TermsEnum> termIters, CharsRefBuilder spare,
                                         BytesRef lastTerm, int[] exhausted) {
        if (getDocFreq(termIters, lastTerm, exhausted) >= request.minDocFreq()) {
            spare.copyUTF8Bytes(lastTerm);
            terms.add(spare.toString());
        }
        BytesRef bytesRef = new BytesRef(lastTerm.utf8ToString());
        lastTerm = bytesRef;
        while (terms.size() < request.size() && lastTerm != null) {
            moveIterators(exhausted, termIters, lastTerm);
            lastTerm = findMinimum(exhausted, termIters);
            if (lastTerm != null) {
                if (getDocFreq(termIters, lastTerm, exhausted) >= request.minDocFreq()) {
                    spare.copyUTF8Bytes(lastTerm);
                    terms.add(spare.toString());
                }
            }
        }
    }

    protected static List<TermsEnum> getTermsEnums(AllTermsShardRequest request, List<LeafReaderContext> leaves) {
        List<TermsEnum> termIters = new ArrayList<>();
        try {
            for (LeafReaderContext reader : leaves) {
                termIters.add(reader.reader().terms(request.field()).iterator());
            }
        } catch (IOException e) {
        }
        return termIters;
    }

    protected static BytesRef findSmallestTermAfter(AllTermsShardRequest request, List<TermsEnum> termIters, BytesRef lastTerm,
                                                    int[] exhausted) throws IOException {
        for (int i = 0; i < termIters.size(); i++) {
            BytesRef curTerm = null;
            if (request.from() != null) {
                // move to the term we want to start after
                TermsEnum.SeekStatus seekStatus = termIters.get(i).seekCeil(new BytesRef(request.from()));
                if (seekStatus.equals(TermsEnum.SeekStatus.END)) {
                    exhausted[i] = 1;
                } else if (seekStatus.equals(TermsEnum.SeekStatus.FOUND)) {
                    curTerm = termIters.get(i).next();
                    if (curTerm == null) {
                        exhausted[i] = 1;
                    }
                } else {
                    curTerm = termIters.get(i).term(); // otherwise we are good
                }

            } else {
                curTerm = termIters.get(i).next();
                if (curTerm == null) {
                    exhausted[i] = 1;// which means there were no terms at all which is odd but I am not sure this cannot happen
                }
            }
            // see it it is the smallest term
            if (exhausted[i] != 1) {
                if (lastTerm == null) {
                    lastTerm = curTerm;
                } else {
                    if (curTerm.compareTo(lastTerm) < 0) {
                        lastTerm = curTerm;
                    }
                }
            }
        }
        return lastTerm;
    }

    protected static long getDocFreq(List<TermsEnum> termIters, BytesRef lastTerm, int[] exhausted) {
        long docFreq = 0;
        for (int i = 0; i < termIters.size(); i++) {
            if (exhausted[i] == 0) {
                try {
                    if (termIters.get(i).term().compareTo(lastTerm) == 0) {
                        docFreq += termIters.get(i).docFreq();
                    }
                } catch (IOException e) {
                }
            }
        }
        return docFreq;
    }

    // returns  copy of the lexicographically smallest term found
    protected static BytesRef findMinimum(int[] exhausted, List<TermsEnum> termIters) {
        BytesRef minTerm = null;
        for (int i = 0; i < termIters.size(); i++) {
            if (exhausted[i] == 1) {
                continue;
            }
            BytesRef candidate = null;
            try {
                candidate = termIters.get(i).term();
            } catch (IOException e) {
            }
            if (minTerm == null) {
                minTerm = candidate;

            } else {
                //it is actually smaller, so we use it
                if (minTerm.compareTo(candidate) > 0) {
                    minTerm = candidate;
                }
            }
        }
        if (minTerm != null) {
            BytesRef ret = new BytesRef(minTerm.utf8ToString());
            return ret;
        }
        return null;
    }

    // last term is expected to be a copy of a term not just some reference into a terms iterator
    protected static void moveIterators(int[] exhausted, List<TermsEnum> termIters, BytesRef lastTerm) {
        try {
            for (int i = 0; i < termIters.size(); i++) {
                if (exhausted[i] == 1) {
                    continue;
                }
                if (termIters.get(i).term().compareTo(lastTerm) == 0) {
                    if (termIters.get(i).next() == null) {
                        exhausted[i] = 1;
                    }
                }
            }
        } catch (IOException e) {
        }
    }
}
