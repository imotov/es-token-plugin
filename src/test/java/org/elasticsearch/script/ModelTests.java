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

import org.dmg.pmml.DataField;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.Model;
import org.dmg.pmml.PMML;
import org.dmg.pmml.RegressionModel;
import org.elasticsearch.test.ESTestCase;
import org.jpmml.model.ImportFilter;
import org.jpmml.model.JAXBUtil;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import javax.xml.transform.Source;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

/**
 */
public class ModelTests extends ESTestCase {

    public void testGenerateLRPMML() throws JAXBException, IOException, SAXException {

        double[] weights = new double[]{randomDouble(), randomDouble(), randomDouble(), randomDouble()};
        double intercept = randomDouble();


        String generatedPMMLModel = PMMLGenerator.generateLRPMMLModel(intercept, weights, new double[]{1, 0});
        PMML hopefullyCorrectPMML;
        try (InputStream is = new ByteArrayInputStream(generatedPMMLModel.getBytes(Charset.defaultCharset()))) {
            Source transformedSource = ImportFilter.apply(new InputSource(is));
            hopefullyCorrectPMML = JAXBUtil.unmarshalPMML(transformedSource);
        }

        String pmmlString = "<PMML xmlns=\"http://www.dmg.org/PMML-4_2\">\n" +
                "    <DataDictionary numberOfFields=\"5\">\n" +
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


    public void testGenerateSVMPMML() throws JAXBException, IOException, SAXException {

        double[] weights = new double[]{randomDouble(), randomDouble(), randomDouble(), randomDouble()};
        double intercept = randomDouble();


        String generatedPMMLModel = PMMLGenerator.generateSVMPMMLModel(intercept, weights, new double[]{1, 0});
        PMML hopefullyCorrectPMML;
        try (InputStream is = new ByteArrayInputStream(generatedPMMLModel.getBytes(Charset.defaultCharset()))) {
            Source transformedSource = ImportFilter.apply(new InputSource(is));
            hopefullyCorrectPMML = JAXBUtil.unmarshalPMML(transformedSource);
        }

        String pmmlString = "<PMML xmlns=\"http://www.dmg.org/PMML-4_2\">\n" +
                "    <DataDictionary numberOfFields=\"5\">\n" +
                "        <DataField name=\"field_0\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"field_1\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"field_2\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"field_3\" optype=\"continuous\" dataType=\"double\"/>\n" +
                "        <DataField name=\"target\" optype=\"categorical\" dataType=\"string\"/>\n" +
                "    </DataDictionary>\n" +
                "    <RegressionModel modelName=\"linear SVM\" functionName=\"classification\" normalizationMethod=\"none\">\n" +
                "        <MiningSchema>\n" +
                "            <MiningField name=\"field_0\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"field_1\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"field_2\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"field_3\" usageType=\"active\"/>\n" +
                "            <MiningField name=\"target\" usageType=\"target\"/>\n" +
                "        </MiningSchema>\n" +
                "        <RegressionTable intercept=\"" + intercept + "\" targetCategory=\"1\">\n" +
                "            <NumericPredictor name=\"field_0\" coefficient=\"" + weights[0] + "\"/>\n" +
                "            <NumericPredictor name=\"field_1\" coefficient=\"" + weights[1] + "\"/>\n" +
                "            <NumericPredictor name=\"field_2\" coefficient=\"" + weights[2] + "\"/>\n" +
                "            <NumericPredictor name=\"field_3\" coefficient=\"" + weights[3] + "\"/>\n" +
                "        </RegressionTable>\n" +
                "        <RegressionTable intercept=\"0.0\" targetCategory=\"0\"/>\n" +
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
        int i = 0;
        for (DataField dataField : model1.getDataDictionary().getDataFields()) {
            DataField otherDataField = model2.getDataDictionary().getDataFields().get(i);
            assertThat(dataField.getDataType(), equalTo(otherDataField.getDataType()));
            assertThat(dataField.getName(), equalTo(otherDataField.getName()));
            i++;
        }

        assertThat(model1.getModels().size(), equalTo(model2.getModels().size()));
        i = 0;
        for (Model model : model1.getModels()) {
            if (model.getModelName().equals("linear SVM")) {
                assertThat(model, instanceOf(RegressionModel.class));
                assertThat(model2.getModels().get(i), instanceOf(RegressionModel.class));
                compareModels((RegressionModel) model, (RegressionModel) model2.getModels().get(i));
            } else if (model.getModelName().equals("logistic regression")) {
                assertThat(model, instanceOf(RegressionModel.class));
                assertThat(model2.getModels().get(i), instanceOf(RegressionModel.class));
                compareModels((RegressionModel) model, (RegressionModel) model2.getModels().get(i));
            } else {
                throw new UnsupportedOperationException("model " + model.getAlgorithmName() +
                        " is not supported and therfore not tested yet");
            }
            i++;
        }
    }

    private static void compareModels(RegressionModel model1, RegressionModel model2) {
        assertThat(model1.getFunctionName().value(), equalTo(model2.getFunctionName().value()));
        assertThat(model1.getFunctionName().value(), equalTo(model2.getFunctionName().value()));
        assertThat(model1.getNormalizationMethod().value(), equalTo(model2.getNormalizationMethod().value()));
        compareMiningFields(model1, model2);
    }

    private static void compareMiningFields(Model model1, Model model2) {
        int i = 0;
        for (MiningField miningField : model1.getMiningSchema().getMiningFields()) {
            MiningField otherMiningField = model2.getMiningSchema().getMiningFields().get(i);
            compareMiningFields(miningField, otherMiningField);
            i++;
        }
    }

    private static void compareMiningFields(MiningField miningField, MiningField otherMiningField) {
        assertThat(miningField.getName(), equalTo(otherMiningField.getName()));
        assertThat(miningField.getUsageType().value(), equalTo(otherMiningField.getUsageType().value()));
    }

}
