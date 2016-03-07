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

package org.elasticsearch.script;

import org.apache.spark.mllib.classification.LogisticRegressionModel;
import org.apache.spark.mllib.classification.SVMModel;
import org.apache.spark.mllib.linalg.DenseVector;
import org.apache.spark.sql.columnar.DOUBLE;
import org.dmg.pmml.*;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.preparespec.PrepareSpecAction;
import org.elasticsearch.action.preparespec.PrepareSpecRequest;
import org.elasticsearch.action.preparespec.PrepareSpecResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.plugin.TokenPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.jpmml.model.ImportFilter;
import org.jpmml.model.JAXBUtil;
import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.equalTo;

/**
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.SUITE)
public class ScriptIT extends ESIntegTestCase {


    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return pluginList(TokenPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(TokenPlugin.class);
    }

    @Test
    public void testVectorScript() throws IOException, ExecutionException, InterruptedException {
        client().prepareIndex().setId("1").setIndex("index").setType("type").setSource("text", "the quick brown fox is quick").get();
        ensureGreen("index");
        refresh();
        XContentBuilder source = jsonBuilder();
        source.startObject()
                .startArray("features")
                .startObject()
                .field("field", "text")
                .field("tokens", "given")
                .field("terms", new String[]{"fox", "quick", "the"})
                .field("number", "tf")
                .field("type", "string")
                .endObject()
                .endArray()
                .field("sparse", false)
                .endObject();
        PrepareSpecResponse specResponse = client().execute(PrepareSpecAction.INSTANCE, new PrepareSpecRequest(source.string(), "index", "type")).get();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("spec_index", specResponse.getIndex());
        parameters.put("spec_type", specResponse.getType());
        parameters.put("spec_id", specResponse.getId());
        SearchResponse searchResponse = client().prepareSearch("index").addScriptField("vector", new Script("vector", ScriptService.ScriptType.INLINE, "native", parameters)).get();
        assertSearchResponse(searchResponse);
        Map<String, Object> vector = (Map<String, Object>) (searchResponse.getHits().getAt(0).field("vector").values().get(0));
        double[] values = (double[]) vector.get("values");
        assertThat(values.length, equalTo(3));
        assertThat(values[0], equalTo(1.0));
        assertThat(values[1], equalTo(2.0));
        assertThat(values[2], equalTo(1.0));

    }

    @Test
    public void testSparseVectorScript() throws IOException, ExecutionException, InterruptedException {
        createIndexWithTermVectors();
        client().prepareIndex().setId("1").setIndex("index").setType("type").setSource("text", "the quick brown fox is quick").get();
        ensureGreen("index");
        refresh();
        XContentBuilder source = jsonBuilder();
        source.startObject()
                .startArray("features")
                .startObject()
                .field("field", "text")
                .field("tokens", "given")
                .field("terms", new String[]{"fox", "lame", "quick", "the"})
                .field("number", "tf")
                .field("type", "string")
                .endObject()
                .endArray()
                .field("sparse", true)
                .endObject();
        PrepareSpecResponse specResponse = client().execute(PrepareSpecAction.INSTANCE, new PrepareSpecRequest(source.string(), "index", "type")).get();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("spec_index", specResponse.getIndex());
        parameters.put("spec_type", specResponse.getType());
        parameters.put("spec_id", specResponse.getId());
        SearchResponse searchResponse = client().prepareSearch("index").addScriptField("vector", new Script("vector", ScriptService.ScriptType.INLINE, "native", parameters)).get();
        assertSearchResponse(searchResponse);
        Map<String, Object> vector = (Map<String, Object>) (searchResponse.getHits().getAt(0).field("vector").values().get(0));
        double[] values = (double[]) vector.get("values");
        assertThat(values.length, equalTo(3));
        assertThat(values[0], equalTo(1.0));
        assertThat(values[1], equalTo(2.0));
        assertThat(values[2], equalTo(1.0));
        int[] indices = (int[]) vector.get("indices");
        assertThat(indices.length, equalTo(3));
        assertThat(indices[0], equalTo(0));
        assertThat(indices[1], equalTo(2));
        assertThat(indices[2], equalTo(3));
    }

    @Test
    // only just checks that nothing crashes
    public void testModelScriptsWithStoredParams() throws IOException, JAXBException, ExecutionException, InterruptedException {
        client().prepareIndex().setId("1").setIndex("index").setType("type").setSource("text", "the quick brown fox is quick").get();
        ensureGreen("index");

        // store LR Model
        String llr = generateLRPMMLModel(0.5, new double[]{1, 2, 3}, new double[]{1, 0});
        // create spec
        client().prepareIndex("model", "pmml", "1").setSource(
                jsonBuilder().startObject()
                        .field("pmml", llr)
                        .endObject()
        ).get();
        refresh();
        XContentBuilder source = jsonBuilder();
        source.startObject()
                .startArray("features")
                .startObject()
                .field("field", "text")
                .field("tokens", "given")
                .field("terms", new String[]{"fox", "quick", "the"})
                .field("number", "occurrence")
                .field("type", "string")
                .endObject()
                .endArray()
                .field("sparse", true)
                .endObject();
        PrepareSpecResponse response = client().execute(PrepareSpecAction.INSTANCE, new PrepareSpecRequest().index("index").type("type").source(source.string())).get();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("index", "model");
        parameters.put("type", "pmml");
        parameters.put("id", "1");
        parameters.put("spec_index", response.getIndex());
        parameters.put("spec_type", response.getType());
        parameters.put("spec_id", response.getId());
        // call PMML script with needed parameters
        SearchResponse searchResponse = client().prepareSearch("index").addScriptField("pmml", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
        assertSearchResponse(searchResponse);
        String label = (String) (searchResponse.getHits().getAt(0).field("pmml").values().get(0));
        logger.info("label: {}", label);
        client().prepareIndex("model", "params", "test_params").setSource(
                jsonBuilder().startObject()
                        .field("weights", new double[]{1, 2, 3})
                        .field("labels", new double[]{0, 1})
                        .field("intercept", 0.5)
                        .field("features", new String[]{"fox", "quick", "the"})
                        .endObject()
        ).get();
        refresh();
        parameters.clear();
        searchResponse = client().prepareSearch("index").addScriptField("svm", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
        assertSearchResponse(searchResponse);
        label = (String) (searchResponse.getHits().getAt(0).field("svm").values().get(0));
        logger.info("label: {}", label);
    }

    @Test
    // only just checks that nothing crashes
    public void testModelScriptsWithStoredParamsAndSparseVectors() throws IOException {
        createIndexWithTermVectors();
        ensureGreen();
        client().prepareIndex().setId("1").setIndex("index").setType("type").setSource("text", "the quick brown fox is quick").get();
        ensureGreen("index");
        client().prepareIndex("model", "params", "test_params").setSource(
                jsonBuilder().startObject()
                        .field("pi", new double[]{1, 2})
                        .field("thetas", new double[][]{{1, 2, 3}, {3, 2, 1}})
                        .field("labels", new double[]{0, 1})
                        .field("features", new String[]{"fox", "quick", "the"})
                        .endObject()
        ).get();
        refresh();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("index", "model");
        parameters.put("type", "params");
        parameters.put("id", "test_params");
        parameters.put("field", "text");
        if (randomBoolean()) {
            parameters.put("fieldDataFields", true);
        }
        SearchResponse searchResponse = client().prepareSearch("index").addScriptField("nb", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
        assertSearchResponse(searchResponse);
        String label = (String) (searchResponse.getHits().getAt(0).field("nb").values().get(0));
        logger.info("label: {}", label);
        client().prepareIndex("model", "params", "test_params").setSource(
                jsonBuilder().startObject()
                        .field("weights", new double[]{1, 2, 3})
                        .field("intercept", 0.5)
                        .field("labels", new double[]{0, 1})
                        .field("features", new String[]{"fox", "quick", "the"})
                        .endObject()
        ).get();
        refresh();
        searchResponse = client().prepareSearch("index").addScriptField("svm", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
        assertSearchResponse(searchResponse);
        label = (String) (searchResponse.getHits().getAt(0).field("svm").values().get(0));
        logger.info("label: {}", label);
    }

    @Test
    // only just checks that nothing crashes
    public void testNaiveBayesUpdateScript() throws IOException {

        client().prepareIndex().setId("1").setIndex("index").setType("type").setSource("text", "the quick brown fox is quick").get();
        refresh();
        client().prepareIndex("model", "params", "test_params").setSource(
                jsonBuilder().startObject()
                        .field("pi", new double[]{1, 2})
                        .field("thetas", new double[][]{{1, 2, 3}, {3, 2, 1}})
                        .field("labels", new double[]{0, 1})
                        .field("features", new String[]{"fox", "quick", "the"})
                        .endObject()
        ).get();
        refresh();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("index", "model");
        parameters.put("type", "params");
        parameters.put("id", "test_params");
        parameters.put("field", "text");
        if (randomBoolean()) {
            parameters.put("fieldDataFields", true);
        }
        client().prepareUpdate().setId("1").setIndex("index").setType("type")
                .setScript(new Script(NaiveBayesUpdateScript.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters))
                .get();

        GetResponse getResponse = client().prepareGet().setId("1").setIndex("index").setType("type").get();
        assertNotNull(getResponse.getSource().get("label"));
        logger.info("label: {}", getResponse.getSource().get("label"));
    }

    @Test
    // only just checks that nothing crashes
    // compares to mllib and fails every now and then because we do not consider the margin
    public void testPMMLSVM() throws IOException, JAXBException, SAXException {
        for (int i = 0; i < 100; i++) {

            double[] modelParams = {randomFloat() * randomIntBetween(-100, +100), randomFloat() * randomIntBetween(-100, +100), randomFloat() * randomIntBetween(-100, +100)};
            SVMModel svmm = new SVMModel(new DenseVector(modelParams), 0.1);
            String pmmlString = "<PMML xmlns=\"http://www.dmg.org/PMML-4_2\">\n" +
                    "    <Header description=\"linear SVM\">\n" +
                    "        <Application name=\"Apache Spark MLlib\" version=\"1.5.2\"/>\n" +
                    "        <Timestamp>2015-12-30T13:51:42</Timestamp>\n" +
                    "    </Header>\n" +
                    "    <DataDictionary numberOfFields=\"4\">\n" +
                    "        <DataField name=\"field_0\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"field_1\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"field_2\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"target\" optype=\"categorical\" dataType=\"string\"/>\n" +
                    "    </DataDictionary>\n" +
                    "    <RegressionModel modelName=\"linear SVM\" functionName=\"classification\" normalizationMethod=\"none\">\n" +
                    "        <MiningSchema>\n" +
                    "            <MiningField name=\"field_0\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"field_1\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"field_2\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"target\" usageType=\"target\"/>\n" +
                    "        </MiningSchema>\n" +
                    "        <RegressionTable intercept=\"0.1\" targetCategory=\"1\">\n" +
                    "            <NumericPredictor name=\"field_0\" coefficient=\"" + modelParams[0] + "\"/>\n" +
                    "            <NumericPredictor name=\"field_1\" coefficient=\"" + modelParams[1] + "\"/>\n" +
                    "            <NumericPredictor name=\"field_2\" coefficient=\"" + modelParams[2] + "\"/>\n" +
                    "        </RegressionTable>\n" +
                    "        <RegressionTable intercept=\"0.0\" targetCategory=\"0\"/>\n" +
                    "    </RegressionModel>\n" +
                    "</PMML>";
            client().prepareIndex("model", "params", "test_params").setSource(jsonBuilder().startObject()
                    .field("pmml", pmmlString)
                    .field("features", new String[]{"fox", "quick", "the", "zonk"})
                    .endObject()).get();
            final GetResponse doc = client().prepareGet("model", "params", "test_params").get();
            PMML pmml;
            try (InputStream is = new ByteArrayInputStream(doc.getSourceAsMap().get("pmml").toString().getBytes(Charset.defaultCharset()))) {
                Source transformedSource = ImportFilter.apply(new InputSource(is));
                pmml = JAXBUtil.unmarshalPMML(transformedSource);
            } catch (SAXException e) {
                throw new ElasticsearchException("could not convert xml to pmml model", e);
            } catch (JAXBException e) {
                throw new ElasticsearchException("could not convert xml to pmml model", e);
            } catch (IOException e) {
                throw new ElasticsearchException("could not convert xml to pmml model", e);
            }

            EsLinearSVMModel esLinearSVMModel = new EsLinearSVMModel((RegressionModel) pmml.getModels().get(0));
            Map<FieldName, Object> params = new HashMap<>();
            int[] vals = new int[]{1, 1, 1, 0};//{randomIntBetween(0, +100), randomIntBetween(0, +100), randomIntBetween(0, +100), 0};
            params.put(new FieldName("field_0"), new Double(vals[0]));
            params.put(new FieldName("field_1"), new Double(vals[1]));
            params.put(new FieldName("field_2"), new Double(vals[2]));
            String result = esLinearSVMModel.evaluate(new Tuple<>(new int[]{0, 1, 2}, new double[]{vals[0], vals[1], vals[2]}));
            double mllibResult = svmm.predict(new DenseVector(new double[]{vals[0], vals[1], vals[2]}));
            assertThat(mllibResult, equalTo(Double.parseDouble(result)));
            // now try the same with pmml script
            String text = "";
            for (int j = 0; j < vals[2]; j++) {
                text = text + "quick ";
            }
            for (int j = 0; j < vals[1]; j++) {
                text = text + "the ";
            }
            for (int j = 0; j < vals[0]; j++) {
                text = text + "fox ";
            }
            client().prepareIndex("test_index", "type", "1").setSource(jsonBuilder().startObject().field("text", text).endObject()).get();
            refresh();
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("index", "model");
            parameters.put("type", "params");
            parameters.put("id", "test_params");
            parameters.put("field", "text");
            parameters.put("fieldDataFields", true);
            SearchResponse searchResponse = client().prepareSearch("test_index").addScriptField("pmml", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
            assertSearchResponse(searchResponse);

            Double label = Double.parseDouble((String) searchResponse.getHits().getAt(0).field("pmml").values().get(0));
            assertThat(label, equalTo(mllibResult));
        }
    }


    @Test
    // only just checks that nothing crashes
    public void testPMMLLR() throws IOException, JAXBException, SAXException {
        for (int i = 0; i < 10; i++) {

            double[] modelParams = new double[]{randomFloat() * randomIntBetween(-100, +100), randomFloat() * randomIntBetween(-100, +100), randomFloat() * randomIntBetween(-100, +100), randomFloat() * randomIntBetween(-100, +100)};
            LogisticRegressionModel lrm = new LogisticRegressionModel(new DenseVector(modelParams), 0.1);
            String pmmlString = "<PMML xmlns=\"http://www.dmg.org/PMML-4_2\">\n" +
                    "    <Header description=\"logistic regression\">\n" +
                    "        <Application name=\"Apache Spark MLlib\" version=\"1.5.2\"/>\n" +
                    "        <Timestamp>2016-01-02T03:57:37</Timestamp>\n" +
                    "    </Header>\n" +
                    "    <DataDictionary numberOfFields=\"4\">\n" +
                    "        <DataField name=\"field_0\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"field_1\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"field_2\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"field_3\" optype=\"continuous\" dataType=\"double\"/>\n" +
                    "        <DataField name=\"target\" optype=\"categorical\" dataType=\"string\"/>\n" +
                    "    </DataDictionary>\n" +
                    "    <RegressionModel modelName=\"logistic regression\" functionName=\"classification\" normalizationMethod=\"logit\">\n" +
                    "        <MiningSchema>\n" +
                    "            <MiningField name=\"field_0\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"field_1\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"field_2\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"field_3\" usageType=\"active\"/>\n" +
                    "            <MiningField name=\"target\" usageType=\"target\"/>\n" +
                    "        </MiningSchema>\n" +
                    "        <RegressionTable intercept=\"0.1\" targetCategory=\"1\">\n" +
                    "            <NumericPredictor name=\"field_0\" coefficient=\"" + modelParams[0] + "\"/>\n" +
                    "            <NumericPredictor name=\"field_1\" coefficient=\"" + modelParams[1] + "\"/>\n" +
                    "            <NumericPredictor name=\"field_2\" coefficient=\"" + modelParams[2] + "\"/>\n" +
                    "            <NumericPredictor name=\"field_3\" coefficient=\"" + modelParams[3] + "\"/>\n" +
                    "        </RegressionTable>\n" +
                    "        <RegressionTable intercept=\"-0.0\" targetCategory=\"0\"/>\n" +
                    "    </RegressionModel>\n" +
                    "</PMML>";
            client().prepareIndex("model", "params", "test_params").setSource(jsonBuilder().startObject()
                    .field("pmml", pmmlString)
                    .field("features", new String[]{"fox", "quick", "the", "zonk"})
                    .endObject()).get();
            GetResponse doc = client().prepareGet("model", "params", "test_params").get();
            PMML pmml;
            try (InputStream is = new ByteArrayInputStream(doc.getSourceAsMap().get("pmml").toString().getBytes(Charset.defaultCharset()))) {
                Source transformedSource = ImportFilter.apply(new InputSource(is));
                pmml = JAXBUtil.unmarshalPMML(transformedSource);
            }
            EsLogisticRegressionModel esLogisticRegressionModel = new EsLogisticRegressionModel((RegressionModel) (pmml.getModels().get(0)));
            Map<FieldName, Object> params = new HashMap<>();
            int[] vals = new int[]{1, 1, 1, 0};//{randomIntBetween(0, +100), randomIntBetween(0, +100), randomIntBetween(0, +100), 0};
            params.put(new FieldName("field_0"), new Double(vals[0]));
            params.put(new FieldName("field_1"), new Double(vals[1]));
            params.put(new FieldName("field_2"), new Double(vals[2]));
            params.put(new FieldName("field_3"), new Double(vals[3]));
            double mllibResult = lrm.predict(new DenseVector(new double[]{vals[0], vals[1], vals[2], vals[3]}));
            String result = esLogisticRegressionModel.evaluate(new Tuple<>(new int[]{0, 1, 2}, new double[]{vals[0], vals[1], vals[2]}));
            assertThat(mllibResult, equalTo(Double.parseDouble(result)));
            // now try the same with pmml script
            String text = "";
            for (int j = 0; j < vals[2]; j++) {
                text = text + "quick ";
            }
            for (int j = 0; j < vals[1]; j++) {
                text = text + "the ";
            }
            for (int j = 0; j < vals[0]; j++) {
                text = text + "fox ";
            }
            client().prepareIndex("test_index", "type", "1").setSource(jsonBuilder().startObject().field("text", text).endObject()).get();
            refresh();
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("index", "model");
            parameters.put("type", "params");
            parameters.put("id", "test_params");
            parameters.put("field", "text");
            parameters.put("fieldDataFields", true);
            SearchResponse searchResponse = client().prepareSearch("test_index").addScriptField("pmml", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
            assertSearchResponse(searchResponse);

            String pmmlLabel = (String) (searchResponse.getHits().getAt(0).field("pmml").values().get(0));
            assertThat(Double.parseDouble(pmmlLabel), equalTo(mllibResult));

            // test mllib lr script
            client().prepareIndex("model", "params", "test_params").setSource(
                    jsonBuilder().startObject()
                            .field("weights", modelParams)
                            .field("intercept", 0.1)
                            .field("labels", new double[]{1, 0})
                            .field("features", new String[]{"fox", "quick", "the", "zonk"})
                            .endObject()
            ).get();
            refresh();
            searchResponse = client().prepareSearch("test_index").addScriptField("lr", new Script(PMMLModel.SCRIPT_NAME, ScriptService.ScriptType.INLINE, "native", parameters)).get();
            assertSearchResponse(searchResponse);
            String label = (String) searchResponse.getHits().getAt(0).field("lr").values().get(0);
            assertThat(Double.parseDouble(label), equalTo(Double.parseDouble(pmmlLabel)));
        }
    }


    void createIndexWithTermVectors() throws IOException {
        XContentBuilder mapping = jsonBuilder();
        mapping.startObject()
                .startObject("type")
                .startObject("properties")
                .startObject("text")
                .field("type", "string")
                .field("term_vector", "yes")
                .endObject()
                .endObject()
                .endObject()
                .endObject();
        client().admin().indices().prepareCreate("index").addMapping("type", mapping).get();
    }

    public void testGenerateLRPMML() throws JAXBException, IOException, SAXException {

        double[] weights = new double[]{randomDouble(), randomDouble(), randomDouble(), randomDouble()};
        double intercept = randomDouble();


        String generatedPMMLModel = generateLRPMMLModel(intercept, new double[]{1, 2, 3}, new double[]{1, 0});
        PMML hopefullyCorrectPMML;
        try (InputStream is = new ByteArrayInputStream(generatedPMMLModel.getBytes(Charset.defaultCharset()))) {
            Source transformedSource = ImportFilter.apply(new InputSource(is));
            hopefullyCorrectPMML = JAXBUtil.unmarshalPMML(transformedSource);
        }

        String pmmlString = "<PMML xmlns=\"http://www.dmg.org/PMML-4_2\">\n" +
                "    <DataDictionary numberOfFields=\"4\">\n" +
                "        <DataField name=\"field_0\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"field_1\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"field_2\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"field_3\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"target\" optype=\"categorical\" dataType=\"string\"/>\n" +
                "    </DataDictionary>\n" +
                "    <RegressionModel modelName=\"logistic regression\" functionName=\"classification\" normalizationMethod=\"logit\">\n" +
                "        <MiningSchema>\n" +
                "            <MiningField name=\"field_0\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"field_1\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"field_2\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"field_3\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"target\" usageType=\"target\"/>\n" +
                "        </MiningSchema>\n" +
                "        <RegressionTable intercept=\"" + Double.toString(intercept) + "\" targetCategory=\"1\">\n" +
                "            <NumericPredictor name=\"field_0\" coefficient=\"" + weights[0] + "\"/>\n" +
                "            <NumericPredictor name=\"field_1\" coefficient=\"" + weights[1] + "\"/>\n" +
                "            <NumericPredictor name=\"field_2\" coefficient=\"" + weights[2] + "\"/>\n" +
                "            <NumericPredictor name=\"field_3\" coefficient=\"" + weights[3] + "\"/>\n" +
                "        </RegressionTable>\n" +
                "        <RegressionTable intercept=\"-0.0\" targetCategory=\"0\"/>\n" +
                "    </RegressionModel>\n" +
                "</PMML>";
        PMML truePMML;
        try (InputStream is = new ByteArrayInputStream(pmmlString.getBytes(Charset.defaultCharset()))) {
            Source transformedSource = ImportFilter.apply(new InputSource(is));
            truePMML = JAXBUtil.unmarshalPMML(transformedSource);
        }

        compareModels(truePMML, hopefullyCorrectPMML);
    }

    public void compareModels(PMML model1, PMML model2) {
        assertThat(model1.getDataDictionary().getNumberOfFields(), equalTo(model2.getDataDictionary().getNumberOfFields()));
    }

    public String generateLRPMMLModel(double intercept, double[] weights, double[] labels) throws JAXBException {
        PMML pmml = new PMML();
        // create DataDictionary
        DataDictionary dataDictionary = createDataDictionary(weights);
        pmml.setDataDictionary(dataDictionary);

        // create model
        RegressionModel regressionModel = new RegressionModel();
        regressionModel.setModelName("logistic regression");
        regressionModel.setFunctionName(MiningFunctionType.CLASSIFICATION);
        regressionModel.setNormalizationMethod(RegressionNormalizationMethodType.LOGIT);
        MiningSchema miningSchema = createMiningSchema(weights);
        regressionModel.setMiningSchema(miningSchema);
        RegressionTable regressionTable0 = new RegressionTable();
        regressionTable0.setIntercept(intercept);
        regressionTable0.setTargetCategory(Double.toString(labels[0]));
        NumericPredictor[] numericPredictors = createNumericPredictors(weights);
        regressionTable0.addNumericPredictors(numericPredictors);
        RegressionTable regressionTable1 = new RegressionTable();
        regressionTable1.setIntercept(0.0);
        regressionTable1.setTargetCategory(Double.toString(labels[1]));
        regressionModel.addRegressionTables(regressionTable0, regressionTable1);
        pmml.addModels(regressionModel);
        // marshal
        return convertPMMLToString(pmml);
        // write to string
    }

    public String generateSVMPMMLModel(double intercept, double[] weights, double[] labels) throws JAXBException {
        PMML pmml = new PMML();
        // create DataDictionary
        DataDictionary dataDictionary = createDataDictionary(weights);
        pmml.setDataDictionary(dataDictionary);

        // create model
        RegressionModel regressionModel = new RegressionModel();
        regressionModel.setModelName("linear SVM");
        regressionModel.setFunctionName(MiningFunctionType.CLASSIFICATION);
        regressionModel.setNormalizationMethod(RegressionNormalizationMethodType.NONE);
        MiningSchema miningSchema = createMiningSchema(weights);
        regressionModel.setMiningSchema(miningSchema);
        RegressionTable regressionTable0 = new RegressionTable();
        regressionTable0.setIntercept(intercept);
        regressionTable0.setTargetCategory(Double.toString(labels[0]));
        NumericPredictor[] numericPredictors = createNumericPredictors(weights);
        regressionTable0.addNumericPredictors(numericPredictors);
        RegressionTable regressionTable1 = new RegressionTable();
        regressionTable1.setIntercept(0.0);
        regressionTable1.setTargetCategory(Double.toString(labels[1]));
        regressionModel.addRegressionTables(regressionTable0, regressionTable1);
        pmml.addModels(regressionModel);
        // marshal
        return convertPMMLToString(pmml);
        // write to string
    }


    public String convertPMMLToString(PMML pmml) throws JAXBException {
        ByteArrayOutputStream baor = new ByteArrayOutputStream();
        StreamResult streamResult = new StreamResult();
        streamResult.setOutputStream(baor);
        JAXBUtil.marshal(pmml, streamResult);
        return baor.toString();
    }

    public DataDictionary createDataDictionary(double[] weights) {
        DataDictionary dataDictionary = new DataDictionary();
        DataField[] dataFields = new DataField[weights.length];
        for (int i = 0; i < weights.length; i++) {
            dataFields[i] = createDataField("field_" + Integer.toString(i), DataType.DOUBLE, OpType.CONTINUOUS);
        }
        dataDictionary.addDataFields(dataFields);
        return dataDictionary;
    }

    public NumericPredictor[] createNumericPredictors(double[] weights) {
        NumericPredictor[] numericPredictors = new NumericPredictor[weights.length];
        for (int i = 0; i < weights.length; i++) {
            numericPredictors[i] = creatNumericPredictor("field_" + Integer.toString(i), weights[i]);
        }
        return numericPredictors;
    }

    public MiningSchema createMiningSchema(double[] weights) {
        MiningSchema miningSchema = new MiningSchema();
        MiningField[] miningFields = new MiningField[weights.length + 1];
        for (int i = 0; i < weights.length; i++) {
            miningFields[i] = creatMiningField("field_" + Integer.toString(i), FieldUsageType.ACTIVE);
        }
        miningFields[weights.length] = creatMiningField("target", FieldUsageType.TARGET);
        miningSchema.addMiningFields(miningFields);
        return miningSchema;
    }

    private NumericPredictor creatNumericPredictor(String name, double coefficient) {
        NumericPredictor numericPredictor = new NumericPredictor();
        numericPredictor.setCoefficient(coefficient);
        numericPredictor.setName(FieldName.create(name));
        return numericPredictor;
    }

    private MiningField creatMiningField(String name, FieldUsageType fieldUsageType) {
        MiningField field = new MiningField();
        field.setName(new FieldName(name));
        field.setUsageType(fieldUsageType);

        return field;

    }

    public DataField createDataField(String name, DataType datatype, OpType opType) {
        DataField field = new DataField();
        field.setName(new FieldName(name));
        field.setDataType(datatype);
        field.setOpType(opType);
        return field;
    }
}