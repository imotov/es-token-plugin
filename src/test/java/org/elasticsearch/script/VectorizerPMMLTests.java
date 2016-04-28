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

import org.dmg.pmml.PMML;
import org.elasticsearch.ElasticsearchException;
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
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.test.StreamsUtils.copyToStringFromClasspath;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;

public class VectorizerPMMLTests extends ESTestCase {

    public void testVectorizerParsing() throws IOException {
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/logistic_regression.xml");
        PMML pmml = AccessController.doPrivileged(new PrivilegedAction<PMML>() {
            public PMML run() {
                try (InputStream is = new ByteArrayInputStream(pmmlString.getBytes(Charset.defaultCharset()))) {
                    Source transformedSource = ImportFilter.apply(new InputSource(is));
                    return JAXBUtil.unmarshalPMML(transformedSource);
                } catch (SAXException e) {
                    throw new ElasticsearchException("could not convert xml to pmml model", e);
                } catch (JAXBException e) {
                    throw new ElasticsearchException("could not convert xml to pmml model", e);
                } catch (IOException e) {
                    throw new ElasticsearchException("could not convert xml to pmml model", e);
                }
            }
        });

        VectorEntries vectorEntries = new VectorEntriesPMML(pmml, 0);
        assertThat(vectorEntries.features.size(), equalTo(14));
    }

    public void testVectorizerParsingWithNormalization() throws IOException {
        final String pmmlString = copyToStringFromClasspath("/org/elasticsearch/script/lr_model.xml");
        PMML pmml = AccessController.doPrivileged(new PrivilegedAction<PMML>() {
            public PMML run() {
                try (InputStream is = new ByteArrayInputStream(pmmlString.getBytes(Charset.defaultCharset()))) {
                    Source transformedSource = ImportFilter.apply(new InputSource(is));
                    return JAXBUtil.unmarshalPMML(transformedSource);
                } catch (SAXException e) {
                    throw new ElasticsearchException("could not convert xml to pmml model", e);
                } catch (JAXBException e) {
                    throw new ElasticsearchException("could not convert xml to pmml model", e);
                } catch (IOException e) {
                    throw new ElasticsearchException("could not convert xml to pmml model", e);
                }
            }
        });

        VectorEntriesPMML vectorEntries = new VectorEntriesPMML(pmml, 0);
        assertThat(vectorEntries.features.size(), equalTo(2));
        final String testData = copyToStringFromClasspath("/org/elasticsearch/script/test.data");
        final String expectedResults = copyToStringFromClasspath("/org/elasticsearch/script/lr_result.txt");
        String testDataLines[] = testData.split("\\r?\\n");
        String expectedResultsLines[] = testData.split("\\r?\\n");
        for (int i = 0; i < testDataLines.length; i++) {
            String[] testDataValues = testDataLines[i].split(",");
            Object ageInput = null;
            if (testDataValues[0].equals("") == false) {
                ageInput = Double.parseDouble(testDataValues[0]);
            }
            Object workInput = null;
            if (testDataValues[1].trim().equals("") == false) {
                workInput = testDataValues[1].trim();
            }
            Map<String, Object> input = new HashMap<>();
            input.put("age", ageInput);
            input.put("work", workInput);
            vectorEntries.vector(input);
        }


    }
}