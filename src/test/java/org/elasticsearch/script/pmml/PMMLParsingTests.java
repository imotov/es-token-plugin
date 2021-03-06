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

package org.elasticsearch.script.pmml;

import org.dmg.pmml.PMML;
import org.dmg.pmml.TreeModel;
import org.elasticsearch.script.MockDataSource;
import org.elasticsearch.script.modelinput.VectorModelInput;
import org.elasticsearch.script.modelinput.VectorModelInputEvaluator;
import org.elasticsearch.script.modelinput.VectorRange;
import org.elasticsearch.script.modelinput.VectorRangesToVectorPMML;
import org.elasticsearch.script.modelinput.MapModelInput;
import org.elasticsearch.script.modelinput.ModelAndModelInputEvaluator;
import org.elasticsearch.test.ESTestCase;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.script.pmml.ProcessPMMLHelper.parsePmml;
import static org.elasticsearch.test.StreamsUtils.copyToStringFromClasspath;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

public class PMMLParsingTests extends ESTestCase {

    public void testSimplePipelineParsingGLM() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/logistic_regression.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        assertThat(((VectorModelInputEvaluator)fieldsToVectorAndModel.getVectorRangesToVector()).getVectorRangeList().size(), equalTo(15));
    }

    public void testTwoStepPipelineParsing() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/lr_model.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries =
                (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(3));
        assertVectorsCorrect(vectorEntries);
    }

    public void testTwoStepPipelineParsingReorderedGLM() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/lr_model_reordered.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(3));
        assertVectorsCorrect(vectorEntries);
    }

    public void assertVectorsCorrect(VectorModelInputEvaluator vectorEntries) throws
            IOException {
        final String testData = copyToStringFromClasspath("/org/elasticsearch/script/test.data");
        final String expectedResults = copyToStringFromClasspath("/org/elasticsearch/script/lr_result.txt");
        String testDataLines[] = testData.split("\\r?\\n");
        String expectedResultsLines[] = expectedResults.split("\\r?\\n");
        for (int i = 0; i < testDataLines.length; i++) {
            String[] testDataValues = testDataLines[i].split(",");
            List<Object> ageInput = new ArrayList<>();
            if (testDataValues[0].equals("") == false) {
                ageInput.add(Double.parseDouble(testDataValues[0]));
            }
            List<Object> workInput = new ArrayList<>();
            if (testDataValues[1].trim().equals("") == false) {
                workInput.add(testDataValues[1].trim());
            }
            Map<String, List<Object>> input = new HashMap<>();
            input.put("age", ageInput);
            input.put("work", workInput);
            Map<String, Object> result = vectorEntries.convert(new MockDataSource(input)).getAsMap();
            String[] expectedResult = expectedResultsLines[i + 1].split(",");
            double expectedAgeValue = Double.parseDouble(expectedResult[0]);
            // assertThat(Double.parseDouble(expectedResult[0]), Matchers.closeTo(((double[]) result.get("values"))[0], 1.e-7));
            if (workInput.size() == 0) {
                // this might be a problem with the model. not sure. the "other" value does not appear in it.
                assertArrayEquals(((double[]) result.get("values")), new double[]{expectedAgeValue, 1.0d}, 1.e-7);
                assertArrayEquals(((int[]) result.get("indices")), new int[]{0, 4});
            } else if ("Private".equals(workInput.get(0))) {
                assertArrayEquals(((double[]) result.get("values")), new double[]{expectedAgeValue, 1.0d, 1.0d}, 1.e-7);
                assertArrayEquals(((int[]) result.get("indices")), new int[]{0, 1, 4});
            } else if ("Self-emp-inc".equals(workInput.get(0))) {
                assertArrayEquals(((double[]) result.get("values")), new double[]{expectedAgeValue, 1.0d, 1.0d}, 1.e-7);
                assertArrayEquals(((int[]) result.get("indices")), new int[]{0, 2, 4});
            } else if ("State-gov".equals(workInput.get(0))) {
                assertArrayEquals(((double[]) result.get("values")), new double[]{expectedAgeValue, 1.0d, 1.0d}, 1.e-7);
                assertArrayEquals(((int[]) result.get("indices")), new int[]{0, 3, 4});
            } else {
                fail("work input was " + workInput);
            }
        }
    }

    public void testModelAndFeatureParsingGLM() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/lr_model.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String>  fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(3));
        assertModelCorrect(fieldsToVectorAndModel);
    }

    public void testBigModelAndFeatureParsingGLM() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/lr_model_adult_full.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String>  fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(15));
        assertBiggerModelCorrect(fieldsToVectorAndModel, "/org/elasticsearch/script/adult.data",
                "/org/elasticsearch/script/knime_glm_adult_result.csv");
    }

    public void testBigModelAndFeatureParsingFromRExportGLM() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/glm-adult-full-r.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String>  fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(12));
        assertBiggerModelCorrect(fieldsToVectorAndModel, "/org/elasticsearch/script/adult.data",
                "/org/elasticsearch/script/r_glm_adult_result" +
                ".csv");
    }

    public void testBigModelCorrectSingleValueGLM() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/lr_model_adult_full.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String>  fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(15));
        assertBiggerModelCorrect(fieldsToVectorAndModel, "/org/elasticsearch/script/singlevalueforintegtest.txt",
                "/org/elasticsearch/script/singleresultforintegtest.txt");
    }

    private void assertModelCorrect(ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel) throws IOException {
        final String testData = copyToStringFromClasspath("/org/elasticsearch/script/test.data");
        final String expectedResults = copyToStringFromClasspath("/org/elasticsearch/script/lr_result.txt");
        String testDataLines[] = testData.split("\\r?\\n");
        String expectedResultsLines[] = expectedResults.split("\\r?\\n");
        for (int i = 0; i < testDataLines.length; i++) {
            String[] testDataValues = testDataLines[i].split(",");
            List<Object> ageInput = new ArrayList<>();
            if (testDataValues[0].equals("") == false) {
                ageInput.add(Double.parseDouble(testDataValues[0]));
            }
            List<Object> workInput = new ArrayList<>();
            if (testDataValues[1].trim().equals("") == false) {
                workInput.add(testDataValues[1].trim());
            }
            Map<String, List<Object>> input = new HashMap<>();
            input.put("age", ageInput);
            input.put("work", workInput);
            @SuppressWarnings("unchecked")
            VectorModelInput result = fieldsToVectorAndModel.getVectorRangesToVector().convert(new MockDataSource(input));
            String[] expectedResult = expectedResultsLines[i + 1].split(",");
            String expectedClass = expectedResult[expectedResult.length - 1];
            expectedClass = expectedClass.substring(1, expectedClass.length() - 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultValues = fieldsToVectorAndModel.getModel().evaluateDebug(result);
            assertThat(expectedClass, equalTo(resultValues.get("class")));
        }
    }

    private void assertBiggerModelCorrect(ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel, String inputData,
                                          String resultData) throws IOException {
        final String testData = copyToStringFromClasspath(inputData);
        final String expectedResults = copyToStringFromClasspath(resultData);
        String testDataLines[] = testData.split("\\r?\\n");
        String expectedResultsLines[] = expectedResults.split("\\r?\\n");
        String[] fields = testDataLines[0].split(",");
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].trim();
            fields[i] = fields[i].substring(1, fields[i].length() - 1);
        }
        for (int i = 1; i < testDataLines.length; i++) {
            String[] testDataValues = testDataLines[i].split(",");
            // trimm spaces and add value
            Map<String, List<Object>> input = new HashMap<>();
            for (int j = 0; j < testDataValues.length; j++) {
                testDataValues[j] = testDataValues[j].trim();
                if (testDataValues[j].equals("") == false) {
                    List<Object> fieldInput = new ArrayList<>();
                    if (j == 0 || j == 2 || j == 4 || j == 10 || j == 11 || j == 12) {
                        fieldInput.add(Double.parseDouble(testDataValues[j]));
                    } else {
                        fieldInput.add(testDataValues[j]);
                    }
                    input.put(fields[j], fieldInput);
                } else {
                    if (randomBoolean()) {
                        input.put(fields[j], new ArrayList<>());
                    }
                }
            }
            VectorModelInput vectorModelInput = fieldsToVectorAndModel.getVectorRangesToVector().convert(new MockDataSource(input));
            String[] expectedResult = expectedResultsLines[i].split(",");
            String expectedClass = expectedResult[2];
            expectedClass = expectedClass.substring(1, expectedClass.length() - 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultValues = fieldsToVectorAndModel.getModel().evaluateDebug(vectorModelInput);
            @SuppressWarnings("unchecked")
            double prob0 = (Double) ((Map<String, Object>) resultValues.get("probs")).get("<=50K");
            @SuppressWarnings("unchecked")
            double prob1 = (Double) ((Map<String, Object>) resultValues.get("probs")).get(">50K");
            assertThat("result " + i + " had wrong probability for class " + "<=50K", prob0,
                    Matchers.closeTo(Double.parseDouble(expectedResult[0]), 1.e-7));
            assertThat("result " + i + " had wrong probability for class " + ">50K", prob1,
                    Matchers.closeTo(Double.parseDouble(expectedResult[1]), 1.e-7));
            assertThat(expectedClass, equalTo(resultValues.get("class")));
        }
    }

    /*tests for tree model*/
    public void testBigModelAndFeatureParsingFromRExportTreeModel() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/tree-adult-full-r.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<MapModelInput, String> fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorRangesToVectorPMML.VectorRangesToVectorPMMLTreeModel vectorEntries = (VectorRangesToVectorPMML
                .VectorRangesToVectorPMMLTreeModel) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getEntries().size(), equalTo(11));
        assertTreeModelModelCorrect(fieldsToVectorAndModel, "/org/elasticsearch/script/adult.data",
                "/org/elasticsearch/script/r_tree_adult_result.csv");
    }

    private void assertTreeModelModelCorrect(ModelAndModelInputEvaluator<MapModelInput, String> fieldsToVectorAndModel, String inputData,
                                             String resultData) throws IOException {
        assertThat(fieldsToVectorAndModel.getModel(), notNullValue());

        final String testData = copyToStringFromClasspath(inputData);
        final String expectedResults = copyToStringFromClasspath(resultData);
        String testDataLines[] = testData.split("\\r?\\n");
        String expectedResultsLines[] = expectedResults.split("\\r?\\n");
        String[] fields = testDataLines[0].split(",");
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].trim();
            fields[i] = fields[i].substring(1, fields[i].length() - 1);
        }
        for (int i = 1; i < testDataLines.length; i++) {
            String[] testDataValues = testDataLines[i].split(",");
            // trimm spaces and add value
            Map<String, List<Object>> input = new HashMap<>();
            for (int j = 0; j < testDataValues.length; j++) {
                testDataValues[j] = testDataValues[j].trim();
                if (testDataValues[j].equals("") == false) {
                    List<Object> fieldInput = new ArrayList<>();
                    if (j == 0 || j == 2 || j == 4 || j == 10 || j == 11 || j == 12) {
                        fieldInput.add(Double.parseDouble(testDataValues[j]));
                    } else {
                        fieldInput.add(testDataValues[j]);
                    }
                    input.put(fields[j], fieldInput);
                } else {
                    if (randomBoolean()) {
                        input.put(fields[j], new ArrayList<>());
                    }
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) ((VectorRangesToVectorPMML) fieldsToVectorAndModel.getVectorRangesToVector())
                    .vector(input);
            String[] expectedResult = expectedResultsLines[i].split(",");
            String expectedClass = expectedResult[expectedResult.length - 1];
            expectedClass = expectedClass.substring(1, expectedClass.length() - 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultValues = fieldsToVectorAndModel.getModel().evaluateDebug(new MapModelInput(result));
            assertThat("result " + i + " has wrong prediction", expectedClass, equalTo(resultValues.get("class")));
        }
    }

    public void testExtractFieldNames() throws IOException {
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/tree-adult-full-r.xml");
        PMML pmml = parsePmml(pmmlString);
        TreeModel treeModel = (TreeModel) pmml.getModels().get(0);
        Set<String> expectedFieldNames = new HashSet<>();
        expectedFieldNames.addAll(Arrays.asList(new String[]{"age_z", "relationship", "marital_status", "hours_per_week_z", "sex",
                "occupation", "education", "education_num_z", "native_country", "race", "workclass"}));
        Set<String> fieldNames = new HashSet<>();
        TreeModelFactory.getFieldNamesFromNode(fieldNames, treeModel.getNode());
        assertThat(expectedFieldNames.size(), equalTo(fieldNames.size()));
        for (String fieldName : expectedFieldNames) {
            assertTrue(fieldNames.contains(fieldName));
        }
    }

    public void testFieldTypeMapExtract() throws IOException {
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/tree-small-r.xml");
        PMML pmml = parsePmml(pmmlString);
        TreeModel treeModel = (TreeModel) pmml.getModels().get(0);
        List<VectorRange> fields = TreeModelFactory.getFieldValuesList(treeModel, pmml.getDataDictionary(),
                pmml.getTransformationDictionary());
        Map<String, String> fieldToTypeMap = TreeModelFactory.getFieldToTypeMap(fields);
        assertTrue(fieldToTypeMap.containsKey("age_z"));
        assertThat(fieldToTypeMap.get("age_z"), equalTo("double"));
        assertTrue(fieldToTypeMap.containsKey("work"));
        assertThat(fieldToTypeMap.get("work"), equalTo("string"));
        assertTrue(fieldToTypeMap.containsKey("education"));
        assertThat(fieldToTypeMap.get("education"), equalTo("string"));
    }
    
    /*tests for naive bayes model*/
    public void testBigModelAndFeatureParsingFromRExportNaiveBayesModel() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/naive-bayes-adult-full-r.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(10));
        assertBiggerModelCorrect(fieldsToVectorAndModel, "/org/elasticsearch/script/naive_bayes_full_single_value.txt",
                "/org/elasticsearch/script/naive_bayes_full_single_result.txt");
    }

    /*tests for naive bayes model*/
    public void testBigModelAndFeatureParsingFromRExportNaiveBayesModelReorderdParams() throws IOException {
        ModelFactories factories = ModelFactories.createDefaultModelFactories();
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/naive-bayes-adult-full-r-reordered.xml");
        PMML pmml = parsePmml(pmmlString);
        ModelAndModelInputEvaluator<VectorModelInput, String> fieldsToVectorAndModel = factories.buildFromPMML(pmml, 0);
        VectorModelInputEvaluator vectorEntries = (VectorModelInputEvaluator) fieldsToVectorAndModel.getVectorRangesToVector();
        assertThat(vectorEntries.getVectorRangeList().size(), equalTo(10));
        assertBiggerModelCorrect(fieldsToVectorAndModel, "/org/elasticsearch/script/naive_bayes_full_single_value.txt",
                "/org/elasticsearch/script/naive_bayes_full_single_result.txt");
    }
}
